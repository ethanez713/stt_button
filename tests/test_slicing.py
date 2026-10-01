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
