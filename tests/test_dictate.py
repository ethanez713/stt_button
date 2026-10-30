import json
import os
import time
import wave

import numpy as np

from stt_button import asr, dictate
from stt_button.cleaner import trim_message_end

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
    assert result.cleaned == trim_message_end("\n\n".join(c.upper() for c in cleaner.calls))
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
    take = idle_take(cleaner, labelled("a", 3), "", "")
    assert cleaner.calls == [" ".join(labelled("a", 3).split(" ")[:18])]

    assert take.idle_step(lambda: np.zeros(0, dtype=np.float32))
    take.mark_stop()
    result = take.finish()

    assert len(cleaner.calls) == 2
    assert (result.chunks, result.idle_cleans, result.idle_hits) == (2, 1, 1)
    assert result.cleaned == trim_message_end("\n\n".join(c.upper() for c in cleaner.calls))


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


def test_given_a_stop_flag_when_asked_then_the_stop_time_is_when_the_flag_was_written(tmp_path):
    flag = tmp_path / "stop"
    flag.touch()
    os.utime(flag, (1000.0, 1000.0))

    assert dictate.stop_requested_at(flag) == 1000.0
    assert dictate.stop_requested_at(tmp_path / "missing") > 1000.0


def test_given_a_pcm_path_when_take_files_are_derived_then_they_sit_beside_it(tmp_path):
    pcm = tmp_path / "20261009T120000-abcdef.pcm"

    stop, cancel, result = dictate.take_files(pcm)

    assert (stop, cancel, result) == (
        tmp_path / "20261009T120000-abcdef.stop",
        tmp_path / "20261009T120000-abcdef.cancel",
        tmp_path / "20261009T120000-abcdef.result.json",
    )


def test_given_a_worker_cmdline_when_parsed_then_the_pcm_is_the_work_argument():
    cmd = b"\0".join([
        b"/usr/bin/python", b"-m", b"stt_button.dictate", b"_work",
        os.fsencode("/mnt/c/takes/a take.pcm"), b"",
    ])

    assert dictate.pcm_from_cmdline(cmd) == dictate.Path("/mnt/c/takes/a take.pcm")
    assert dictate.pcm_from_cmdline(b"python\0-m\0stt_button.dictate\0stop\0") is None


def _isolate_take(monkeypatch, tmp_path, running):
    monkeypatch.setattr(dictate, "DICTATION", tmp_path)
    monkeypatch.setattr(dictate, "PIDFILE", tmp_path / "worker.pid")
    monkeypatch.setattr(dictate, "running_pcm", lambda pidfile=None: running)


def test_given_a_worker_already_on_the_pcm_when_start_is_asked_then_it_is_started_without_spawning(tmp_path, monkeypatch):
    pcm = tmp_path / "a.pcm"
    _isolate_take(monkeypatch, tmp_path, pcm)

    def popen(*args, **kwargs):
        raise AssertionError("spawned")

    monkeypatch.setattr(dictate.subprocess, "Popen", popen)

    assert dictate.start(str(pcm)) == {"status": "started"}
    assert not (tmp_path / "worker.pid").exists()


def test_given_a_worker_on_another_pcm_when_start_is_asked_then_it_is_busy(tmp_path, monkeypatch):
    _isolate_take(monkeypatch, tmp_path, tmp_path / "other.pcm")

    def popen(*args, **kwargs):
        raise AssertionError("spawned")

    monkeypatch.setattr(dictate.subprocess, "Popen", popen)

    result = dictate.start(str(tmp_path / "a.pcm"))

    assert result == {"status": "error", "error": "a take is already open"}


def test_given_no_worker_when_start_is_asked_then_it_spawns_and_keeps_a_stop_file(tmp_path, monkeypatch):
    pcm = tmp_path / "a.pcm"
    stop = pcm.with_suffix(".stop")
    stop.write_text("asked")
    _isolate_take(monkeypatch, tmp_path, None)
    spawned = {}

    class Proc:
        pid = 4242

    def popen(args, **kwargs):
        spawned["args"] = args
        return Proc()

    monkeypatch.setattr(dictate.subprocess, "Popen", popen)

    assert dictate.start(str(pcm)) == {"status": "started"}
    assert spawned["args"][-2:] == ["_work", str(pcm)]
    assert (tmp_path / "worker.pid").read_text() == "4242"
    assert stop.read_text() == "asked"


def test_given_a_reader_holding_the_result_when_written_then_the_replace_is_retried(tmp_path, monkeypatch):
    path = tmp_path / "take.result.json"
    real_replace = dictate.Path.replace
    failures = {"left": 2}

    def replace(self, target):
        if failures["left"] and self.suffix == ".tmp":
            failures["left"] -= 1
            raise PermissionError("sharing violation")
        return real_replace(self, target)

    monkeypatch.setattr(dictate.Path, "replace", replace)
    monkeypatch.setattr(dictate.time, "sleep", lambda seconds: None)

    dictate.write_result({"status": "text", "text": "kept"}, path)

    assert failures["left"] == 0
    assert json.loads(path.read_text()) == {"status": "text", "text": "kept"}
    assert not path.with_suffix(".tmp").exists()


def test_given_the_workers_pcm_when_stop_is_asked_then_the_stop_file_is_written_and_the_result_returned(tmp_path, monkeypatch):
    pcm = tmp_path / "take.pcm"
    monkeypatch.setattr(dictate, "running_pcm", lambda pidfile=None: pcm)
    dictate.write_result({"status": "text", "text": "hello"}, pcm.with_suffix(".result.json"))

    assert dictate.stop() == {"status": "text", "text": "hello"}
    assert pcm.with_suffix(".stop").is_file()
    assert not pcm.with_suffix(".result.json").exists()


def test_given_the_workers_pcm_when_cancel_is_asked_then_the_cancel_file_is_written(tmp_path, monkeypatch):
    pcm = tmp_path / "take.pcm"
    monkeypatch.setattr(dictate, "running_pcm", lambda pidfile=None: pcm)

    assert dictate.cancel() == {"status": "cancelled"}
    assert pcm.with_suffix(".cancel").is_file()


def test_given_no_worker_when_stop_or_cancel_is_asked_then_nothing_is_opened(tmp_path, monkeypatch):
    monkeypatch.setattr(dictate, "running_pcm", lambda pidfile=None: None)

    assert dictate.stop()["error"].startswith("no take was open")
    assert dictate.cancel() == {"status": "idle"}
    assert list(tmp_path.iterdir()) == []


def test_given_a_worker_that_vanishes_when_stop_waits_then_it_reports_no_take(tmp_path, monkeypatch):
    pcm = tmp_path / "take.pcm"
    seen = {"n": 0}

    def running(pidfile=None):
        seen["n"] += 1
        return pcm if seen["n"] == 1 else None

    monkeypatch.setattr(dictate, "running_pcm", running)
    monkeypatch.setattr(dictate, "RESULT_TIMEOUT_S", 1)
    monkeypatch.setattr(dictate.time, "sleep", lambda seconds: None)

    result = dictate.stop()

    assert result["status"] == "error" and result["error"].startswith("no take was open")
    assert not pcm.with_suffix(".stop").exists()


def test_given_start_requests_when_claimed_then_each_pcm_is_returned_once_and_the_request_is_gone(tmp_path):
    (tmp_path / "a.start").touch()
    (tmp_path / "b.start").touch()
    (tmp_path / "a.pcm").write_bytes(b"\0\0")
    (tmp_path / "c.bubble.json").write_text("{}")

    claimed = sorted(dictate.claim_starts(tmp_path))

    assert claimed == [tmp_path / "a.pcm", tmp_path / "b.pcm"]
    assert dictate.claim_starts(tmp_path) == []
    assert (tmp_path / "a.pcm").exists()


def test_given_a_start_that_fails_when_served_then_the_error_is_the_takes_result(tmp_path, monkeypatch):
    monkeypatch.setattr(dictate, "start", lambda pcm: {"status": "error", "error": "a take is already open"})

    dictate.serve_start(tmp_path / "t.pcm")

    assert dictate.take_result(tmp_path / "t.result.json") == {"status": "error", "error": "a take is already open"}


def test_given_start_raising_when_served_then_the_exception_becomes_the_takes_result(tmp_path, monkeypatch):
    def boom(pcm):
        raise OSError("no space")

    monkeypatch.setattr(dictate, "start", boom)

    dictate.serve_start(tmp_path / "t.pcm")

    assert dictate.take_result(tmp_path / "t.result.json") == {"status": "error", "error": "OSError: no space"}


def test_given_a_start_that_succeeds_when_served_then_no_result_is_written(tmp_path, monkeypatch):
    monkeypatch.setattr(dictate, "start", lambda pcm: {"status": "started"})

    dictate.serve_start(tmp_path / "t.pcm")

    assert not (tmp_path / "t.result.json").exists()


def test_given_the_loop_when_stepping_then_the_heartbeat_is_touched_every_interval_and_start_requests_are_served(tmp_path, monkeypatch):
    served = []
    monkeypatch.setattr(dictate, "serve_start", served.append)
    heartbeat = tmp_path / dictate.HEARTBEAT

    beat = dictate.watch_step(tmp_path, float("-inf"), 100.0)
    assert heartbeat.exists() and beat == 100.0
    os.utime(heartbeat, (1, 1))

    (tmp_path / "t.start").touch()
    beat = dictate.watch_step(tmp_path, beat, 100.0 + dictate.HEARTBEAT_S - 0.1)
    assert heartbeat.stat().st_mtime == 1 and beat == 100.0
    assert served == [tmp_path / "t.pcm"]

    beat = dictate.watch_step(tmp_path, beat, 100.0 + dictate.HEARTBEAT_S)
    assert heartbeat.stat().st_mtime > 1 and beat == 100.0 + dictate.HEARTBEAT_S
