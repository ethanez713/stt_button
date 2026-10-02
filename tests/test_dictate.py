import os
import time
import wave

import numpy as np

from stt_button import asr, dictate

SR = asr.SAMPLE_RATE


def speech_like(seconds, silences=()):
    t = np.arange(int(seconds * SR)) / SR
    samples = 0.3 * np.sin(2 * np.pi * 220 * t).astype(np.float32)
    for start, end in silences:
        samples[int(start * SR):int(end * SR)] = 0
    return samples


def write_wav(path, seconds):
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(b"\0\0" * int(seconds * SR))


def test_given_audio_fed_in_half_second_pieces_when_sliced_then_cuts_match_whole_audio():
    audio = speech_like(95, [(27.0, 27.2), (58.0, 58.3), (81.0, 81.1)])
    slicer = asr.Slicer(30, 5)
    streamed = [cut for piece in np.array_split(audio, 190) for cut in slicer.feed(piece)] + slicer.finish()

    assert streamed == asr.slice_bounds(audio, 30, 5)


def test_given_eleven_minutes_of_takes_when_retained_then_oldest_deleted_down_to_ten(tmp_path):
    names = ["a", "b", "c", "d"]
    for age, name in enumerate(names):
        write_wav(tmp_path / f"{name}.wav", 200 if name != "d" else 60)
        os.utime(tmp_path / f"{name}.wav", (1000 + age, 1000 + age))

    deleted = dictate.retain(tmp_path, keep_s=600)

    # Newest first: d (60) + c (200) + b (200) = 460 s kept; adding a (200) would pass 600.
    assert [p.name for p in deleted] == ["a.wav"]
    assert sorted(p.name for p in tmp_path.glob("*.wav")) == ["b.wav", "c.wav", "d.wav"]


def test_given_a_written_result_when_taken_then_it_is_consumed_once(tmp_path):
    path = tmp_path / "result.json"
    dictate.write_result({"status": "text", "text": "héllo\nworld"}, path)

    assert dictate.take_result(path) == {"status": "text", "text": "héllo\nworld"}
    assert dictate.take_result(path) is None


def test_given_a_pidfile_for_a_dead_or_foreign_process_when_checked_then_no_worker(tmp_path):
    pidfile = tmp_path / "worker.pid"
    pidfile.write_text(str(os.getpid()))  # this pytest process is alive but is not a dictate worker

    assert dictate.running_worker(pidfile) is None


def test_given_no_pidfile_when_checked_then_no_worker(tmp_path):
    assert dictate.running_worker(tmp_path / "missing.pid") is None


def test_given_pcm_bytes_when_wrapped_then_wav_duration_matches(tmp_path):
    pcm = tmp_path / "t.pcm"
    pcm.write_bytes(b"\0\0" * SR * 3)

    assert dictate.pcm_to_wav(pcm, tmp_path / "t.wav") == 3.0
    with wave.open(str(tmp_path / "t.wav")) as w:
        assert (w.getframerate(), w.getnchannels(), w.getnframes()) == (SR, 1, SR * 3)


def test_given_a_newer_take_owns_the_pidfile_when_an_old_worker_releases_then_it_is_kept(tmp_path):
    pidfile = tmp_path / "worker.pid"
    pidfile.write_text("222")
    stop_flag = tmp_path / "stop"
    stop_flag.touch()

    dictate.release_take(111, pidfile, (stop_flag,))

    assert pidfile.read_text() == "222" and stop_flag.exists()


def test_given_its_own_pidfile_when_a_worker_releases_then_it_is_removed(tmp_path):
    pidfile = tmp_path / "worker.pid"
    pidfile.write_text("111")
    stop_flag = tmp_path / "stop"
    stop_flag.touch()

    dictate.release_take(111, pidfile, (stop_flag,))

    assert not pidfile.exists() and not stop_flag.exists()


def test_given_a_write_that_ends_mid_sample_when_tailed_then_the_half_sample_waits_for_the_rest(tmp_path):
    pcm = tmp_path / "t.pcm"
    samples = np.array([1000, -2000, 3000], dtype="<i2").tobytes()
    tail = dictate.PcmTail(pcm)
    pcm.write_bytes(samples[:3])

    first = tail.read()
    with pcm.open("ab") as f:
        f.write(samples[3:])
    second = tail.read()

    assert (np.concatenate([first, second]) * 32768).round().astype(int).tolist() == [1000, -2000, 3000]


def test_given_no_file_yet_when_tailed_then_empty(tmp_path):
    assert dictate.PcmTail(tmp_path / "missing.pcm").read().size == 0


class ScriptedRecognizer:
    def __init__(self, *texts):
        self.texts = list(texts)

    def recognize(self, samples):
        return self.texts.pop(0)

    def recognize_after(self, audio, junction_s):
        return ".", self.texts.pop(0)


class UppercaseCleaner:
    backend = "stub"

    def __init__(self):
        self.calls = []
        self.completed = []

    def count_tokens(self, text):
        return len(text.split())

    def clean_chunk(self, chunk, cancelled=None):
        self.calls.append(chunk)
        for _ in range(5 if cancelled else 0):
            time.sleep(0.03)
            if cancelled():
                return None
        self.completed.append(chunk)
        return chunk.upper(), 1


def labelled(prefix, count):
    return " ".join(" ".join([f"{prefix}{i}"] * 9) + "." for i in range(1, count + 1))


def live_cfg():
    from stt_button import config

    return {**config.load(), "chunk_min_tokens": 15, "skip_max_words": 5}


def test_given_a_three_slice_take_when_fed_then_chunks_are_cleaned_before_the_stop_and_joined_by_blank_lines():
    from stt_button.live import LiveTake

    cleaner = UppercaseCleaner()
    slices = [labelled("a", 3), labelled("b", 3), labelled("c", 1)]
    take = LiveTake(ScriptedRecognizer(*slices), lambda: cleaner, live_cfg())

    take.feed(speech_like(65))
    assert len(cleaner.calls) == 2
    take.mark_stop()
    result = take.finish()

    assert len(cleaner.calls) == 4
    assert result.chunks == 4 and result.clean_mode == "chunks"
    assert result.cleaned == "\n\n".join(c.upper() for c in cleaner.calls)
    assert result.raw == " ".join(slices)


def test_given_a_five_word_take_when_finished_then_it_is_raw_and_the_cleaner_is_never_asked_for():
    from stt_button.live import LiveTake

    def forbidden():
        raise AssertionError("the cleaner must not be touched")

    take = LiveTake(ScriptedRecognizer("Meet at noon, okay."), forbidden, live_cfg())
    take.feed(speech_like(3))
    result = take.finish()

    assert (result.cleaned, result.chunks, result.clean_mode) == ("Meet at noon okay", 0, "raw")


def spoken_then_silent(speech_s, silence_s):
    from tests import make_vectors

    return make_vectors.to_float(make_vectors.pcm_signal(
        [["silence", 0.3], ["noise", speech_s, 8000], ["silence", silence_s]]))


def idle_take(cleaner, *texts):
    from stt_button.live import LiveTake

    take = LiveTake(ScriptedRecognizer(*texts), lambda: cleaner, live_cfg())
    take.feed(spoken_then_silent(3, 3))
    return take


def test_given_a_pause_when_idle_then_the_stop_finds_the_remainder_already_cleaned():
    cleaner = UppercaseCleaner()
    take = idle_take(cleaner, labelled("a", 3), "")
    assert cleaner.calls == [" ".join(labelled("a", 3).split(" ")[:18])]

    assert take.idle_step(lambda: np.zeros(0, dtype=np.float32))
    take.mark_stop()
    result = take.finish()

    assert len(cleaner.calls) == 2
    assert (result.chunks, result.idle_cleans, result.idle_hits) == (2, 1, 1)
    assert result.cleaned == "\n\n".join(c.upper() for c in cleaner.calls)


def test_given_speech_during_an_idle_clean_when_polled_then_it_is_cancelled_and_the_text_is_unchanged():
    cleaner = UppercaseCleaner()
    take = idle_take(cleaner, labelled("a", 3), labelled("b", 1))
    speech = spoken_then_silent(1, 0)

    take.idle_step(lambda: speech)
    assert cleaner.completed == cleaner.calls[:1]
    assert len(cleaner.calls) == 2
    take.mark_stop()
    result = take.finish()

    assert result.raw == labelled("a", 3) + " " + labelled("b", 1)
    assert result.cleaned.count("\n\n") == result.chunks - 1
    assert result.idle_hits == 0
    assert len(cleaner.completed) == len(set(cleaner.completed))


def test_given_everything_cleaned_when_idle_again_then_the_cleaner_is_not_called():
    cleaner = UppercaseCleaner()
    take = idle_take(cleaner, labelled("a", 3), "")
    take.idle_step(lambda: np.zeros(0, dtype=np.float32))
    calls = len(cleaner.calls)

    assert not take.idle_step(lambda: np.zeros(0, dtype=np.float32))
    assert len(cleaner.calls) == calls


def test_given_a_short_take_when_idle_then_nothing_is_cleaned():
    cleaner = UppercaseCleaner()
    take = idle_take(cleaner, "Meet at noon, okay.", "")
    assert not take.idle_step(lambda: np.zeros(0, dtype=np.float32))
    assert cleaner.calls == []


class SwappingCleaner(UppercaseCleaner):
    def clean_chunk(self, chunk, cancelled=None):
        return chunk.replace("S20", "S25").replace("it ", "it, ", 1), 1


def test_given_a_clean_that_swaps_a_model_name_when_finished_then_the_chunk_is_inserted_as_heard():
    from stt_button.live import LiveTake

    take = LiveTake(ScriptedRecognizer("Put it on the S20 Ultra please now"), lambda: SwappingCleaner(), live_cfg())
    take.feed(speech_like(3))
    result = take.finish()

    assert result.cleaned == "Put it on the S20 Ultra please now"
    assert result.drifts == 1


def test_given_a_clean_that_keeps_the_names_when_finished_then_it_is_kept_and_no_drift_is_counted():
    from stt_button.live import LiveTake

    take = LiveTake(ScriptedRecognizer("Put it on the Ultra please now"), lambda: SwappingCleaner(), live_cfg())
    take.feed(speech_like(3))
    result = take.finish()

    assert result.cleaned == "Put it, on the Ultra please now"
    assert result.drifts == 0
