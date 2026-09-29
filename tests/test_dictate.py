import os
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
