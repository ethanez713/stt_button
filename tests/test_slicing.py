import wave

import numpy as np
import pytest

from stt_button import asr

SR = asr.SAMPLE_RATE


def speech_like(seconds, silences=()):
    """Loud tone everywhere except the given (start_s, end_s) silences."""
    t = np.arange(int(seconds * SR)) / SR
    samples = 0.3 * np.sin(2 * np.pi * 220 * t).astype(np.float32)
    for start, end in silences:
        samples[int(start * SR):int(end * SR)] = 0
    return samples


def test_cuts_land_inside_pauses():
    silences = [(27.0, 27.2), (57.0, 57.2)]
    bounds = asr.slice_bounds(speech_like(75, silences), slice_max_s=30, search_s=5)

    assert len(bounds) == 3
    for (_, cut), (start, end) in zip(bounds, silences):
        assert start * SR <= cut <= end * SR
    assert bounds[-1][1] == 75 * SR


def test_without_pauses_every_slice_still_fits():
    bounds = asr.slice_bounds(speech_like(95), slice_max_s=30, search_s=5)

    assert all(end - start <= 30 * SR for start, end in bounds)
    assert bounds[0][0] == 0 and bounds[-1][1] == 95 * SR
    assert all(a[1] == b[0] for a, b in zip(bounds, bounds[1:]))


def test_audio_within_one_slice_is_not_cut():
    assert asr.slice_bounds(speech_like(30), 30, 5) == [(0, 30 * SR)]


def test_empty_audio_has_no_slices():
    assert asr.slice_bounds(np.zeros(0, dtype=np.float32), 30, 5) == []


def test_read_wav_scales_and_rejects_wrong_rate(tmp_path):
    good = tmp_path / "good.wav"
    with wave.open(str(good), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(np.array([16384, -32768], dtype="<i2").tobytes())
    assert asr.read_wav(good).tolist() == [0.5, -1.0]

    bad = tmp_path / "bad.wav"
    with wave.open(str(bad), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(44100)
        w.writeframes(b"\0\0")
    with pytest.raises(ValueError):
        asr.read_wav(bad)


def vector_cases():
    from tests import make_vectors

    return [(name, segments, slice_max_s, search_s, (pause[0] if pause else {}))
            for name, (segments, slice_max_s, search_s, *pause) in make_vectors.SLICER_CASES.items()]


@pytest.mark.parametrize("name, segments, slice_max_s, search_s, options", vector_cases())
@pytest.mark.parametrize("piece", [777, 1600, 4000, 48000])
def test_pieces_cut_exactly_where_the_whole_signal_does(name, segments, slice_max_s, search_s, options, piece):
    from tests import make_vectors

    audio = make_vectors.to_float(make_vectors.pcm_signal(segments))
    slicer = asr.Slicer(slice_max_s, search_s, **options)
    streamed = [cut for start in range(0, len(audio), piece) for cut in slicer.feed(audio[start:start + piece])]
    streamed += slicer.finish()

    assert streamed == asr.slice_bounds(audio, slice_max_s, search_s, **options)


def test_pause_state_follows_the_audio():
    from tests import make_vectors

    pause = make_vectors.PAUSE
    slicer = asr.Slicer(30, 5, **pause)
    audio = make_vectors.to_float(make_vectors.pcm_signal(
        [["silence", 3], ["noise", 1, 8000], ["silence", 0.5], ["silence", 0.5]]))

    slicer.feed(audio[:int(4.5 * SR)])
    assert not slicer.paused
    assert slicer.last_speech_end == 4 * SR
    slicer.feed(audio[int(4.5 * SR):])
    assert slicer.paused


def test_without_pause_s_nothing_is_ever_paused():
    slicer = asr.Slicer(30, 5)
    slicer.feed(np.zeros(10 * SR, dtype=np.float32))
    assert not slicer.paused and slicer.last_speech_end is None


# Same audio and cut offsets as android/.../SlicerCutTest.kt.
# 0.5 s of silence, 3 s of amplitude-8000 noise, then silence. The 0.5 s cut falls at 60000;
# the 1.6 s cut at 68800. Leading silence keeps the noise above the floor.
SPEECH_THEN_SILENCE = [["silence", 0.5], ["noise", 3, 8000], ["silence", 2]]
CUT_AT_FIELD_PAUSE = 60000
CUT_AT_BUFFER_PAUSE = 68800
# 64800 is the centre of a 0.1 s gap at 4 s inside the 8 s field slice.
# 16800 is the centre of a dip at 1 s; a quieter dip at 0.5 s sits outside that search.
FIELD_HARD_CUT = 64800
FIELD_CAPPED_CUT = 16800


def _float(segments):
    from tests import make_vectors

    return make_vectors.to_float(make_vectors.pcm_signal(segments))


def _gapped(seconds, *gaps):
    from tests import make_vectors

    pcm = make_vectors.pcm_signal([["noise", seconds, 8000]])
    for start in gaps:
        at = int(start * SR)
        pcm[at:at + SR // 10] = 0
    return make_vectors.to_float(pcm)


def _dipped():
    """Constant samples, so a lower dip is the unique quietest window and neither dip is speech."""
    pcm = np.full(9 * SR, 8000, dtype=np.int16)
    pcm[SR // 2:SR // 2 + SR // 10] = 5000
    pcm[SR:SR + SR // 10] = 6000
    return pcm.astype(np.float32) / 32768


def test_cut_pause_closes_a_slice_while_paused_waits_for_pause_s():
    audio = _float(SPEECH_THEN_SILENCE)
    slicer = asr.Slicer(30, 5, 1.6, 2, 10, cut_pause_s=0.5)

    early = slicer.feed(audio[:int(4.0 * SR)])
    assert early == [(0, CUT_AT_FIELD_PAUSE)]
    assert not slicer.paused
    assert slicer.feed(audio[int(4.0 * SR):int(5.0 * SR)]) == []
    assert not slicer.paused
    assert slicer.feed(audio[int(5.0 * SR):int(5.1 * SR)]) == []
    assert slicer.paused

    whole = asr.Slicer(30, 5, 1.6, 2, 10, cut_pause_s=0.5)
    assert whole.feed(audio[:int(5.1 * SR)]) == [(0, CUT_AT_FIELD_PAUSE)]
    assert whole.paused


def test_field_hard_cut_lands_at_field_slice_max():
    from stt_button import config

    audio = _gapped(9, 4.0)
    slicer = asr.Slicer.from_config_for_field(config.load())
    cuts = slicer.feed(audio)

    assert cuts == [(0, FIELD_HARD_CUT)]
    assert cuts[0][1] <= 8 * SR
    assert not slicer.paused


def test_field_factory_search_window_fits_inside_the_hard_cut():
    from stt_button import config

    cfg = {**config.load(), "slice_search_s": 100}
    audio = _dipped()
    slicer = asr.Slicer.from_config_for_field(cfg)
    cuts = slicer.feed(audio)

    assert cuts == [(0, FIELD_CAPPED_CUT)]
    assert cuts[0][1] < cfg["field_slice_max_s"] * SR


def test_buffer_factory_keeps_pause_s_and_slice_max_s():
    from stt_button import config

    cfg = config.load()
    audio = _float(SPEECH_THEN_SILENCE)
    buffered = asr.Slicer.from_config(cfg)
    plain = asr.Slicer(cfg["slice_max_s"], cfg["slice_search_s"], cfg["pause_s"], cfg["slice_min_s"],
                       cfg["pause_margin_db"])
    explicit = asr.Slicer(cfg["slice_max_s"], cfg["slice_search_s"], cfg["pause_s"], cfg["slice_min_s"],
                          cfg["pause_margin_db"], cut_pause_s=cfg["pause_s"])

    assert buffered.feed(audio[:int(4.0 * SR)]) == []
    assert not buffered.paused
    rest = audio[int(4.0 * SR):int(5.1 * SR)]
    plain.feed(audio[:int(4.0 * SR)])
    assert buffered.feed(rest) == plain.feed(rest) == [(0, CUT_AT_BUFFER_PAUSE)]
    assert buffered.paused and plain.paused
    assert explicit.feed(audio[:int(5.1 * SR)]) == [(0, CUT_AT_BUFFER_PAUSE)]

    long = _float([["noise", 35, 8000]])
    bounds = asr.slice_bounds(long, cfg["slice_max_s"], cfg["slice_search_s"], pause_s=cfg["pause_s"],
                              slice_min_s=cfg["slice_min_s"], pause_margin_db=cfg["pause_margin_db"])
    got = asr.Slicer.from_config(cfg)
    assert got.feed(long) + got.finish() == bounds
    assert 8 * SR < bounds[0][1] <= 30 * SR
