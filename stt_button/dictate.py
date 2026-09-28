"""Hotkey dictation on this machine: one worker process per take.

    pyrun python -m stt_button.dictate toggle   # start a take, or stop it and print the text
    pyrun python -m stt_button.dictate cancel   # discard the open take

Each command prints one JSON object: {"status": "recording"}, {"status": "text", "text": ...},
{"status": "cancelled"}, {"status": "idle"}, or {"status": "error", "error": ...}.
"""

import gc
import json
import os
import signal
import subprocess
import sys
import threading
import time
import uuid
import wave
from datetime import datetime, timezone
from pathlib import Path

import numpy as np

from stt_button import config
from stt_button.asr import SAMPLE_RATE, Slicer, join_slices

DICTATION = config.REPO / "data" / "dictation"
PIDFILE = DICTATION / "worker.pid"
RESULT = DICTATION / "result.json"
LOG = DICTATION / "log.jsonl"
KEEP_AUDIO_S = 600
POLL_S = 0.5
RESULT_TIMEOUT_S = 600
START_TIMEOUT_S = 5


def write_result(result: dict, path: Path = RESULT) -> None:
    partial = path.with_suffix(".tmp")
    partial.write_text(json.dumps(result, ensure_ascii=False))
    partial.replace(path)


def take_result(path: Path = RESULT) -> dict | None:
    """Read and consume the finished take's result, if there is one."""
    try:
        result = json.loads(path.read_text())
    except FileNotFoundError:
        return None
    path.unlink(missing_ok=True)
    return result


def running_worker(pidfile: Path = PIDFILE) -> int | None:
    try:
        pid = int(pidfile.read_text())
        cmdline = Path(f"/proc/{pid}/cmdline").read_bytes()
    except (FileNotFoundError, ValueError):
        return None
    return pid if b"stt_button.dictate" in cmdline else None


def retain(directory: Path, keep_s: float) -> list[Path]:
    """Delete the oldest wavs beyond keep_s seconds of total audio; return what was deleted."""
    wavs = sorted(directory.glob("*.wav"), key=lambda p: p.stat().st_mtime, reverse=True)
    total, deleted = 0.0, []
    for wav in wavs:
        with wave.open(str(wav)) as w:
            total += w.getnframes() / w.getframerate()
        if total > keep_s:
            wav.unlink()
            deleted.append(wav)
    return deleted


def pcm_to_wav(pcm: Path, wav: Path) -> float:
    data = pcm.read_bytes()
    with wave.open(str(wav), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SAMPLE_RATE)
        w.writeframes(data)
    return len(data) / 2 / SAMPLE_RATE


class _PcmTail:
    """Reads the samples appended to a raw s16le file since the last read."""

    def __init__(self, path: Path):
        self.path = path
        self.offset = 0

    def read(self) -> np.ndarray:
        try:
            with self.path.open("rb") as f:
                f.seek(self.offset)
                data = f.read()
        except FileNotFoundError:
            return np.zeros(0, dtype=np.float32)
        data = data[:len(data) // 2 * 2]
        self.offset += len(data)
        return np.frombuffer(data, dtype="<i2").astype(np.float32) / 32768


def work(take_id: str) -> None:
    from stt_button.asr import Recognizer
    from stt_button.cleaner import Cleaner

    cfg = config.load()
    stop, cancelled = threading.Event(), threading.Event()
    signal.signal(signal.SIGUSR1, lambda *_: stop.set())
    signal.signal(signal.SIGTERM, lambda *_: (cancelled.set(), stop.set()))
    pcm = DICTATION / f"{take_id}.pcm"
    recorder = subprocess.Popen(
        ["parec", "--device=RDPSource", f"--rate={SAMPLE_RATE}", "--channels=1", "--format=s16le", str(pcm)],
    )
    started = time.monotonic()
    try:
        # Loads while the user is already talking; parec has been capturing since before this line.
        recognizer = Recognizer()
        slicer = Slicer(cfg["slice_max_s"], cfg["slice_search_s"])
        tail = _PcmTail(pcm)
        texts = []
        while not stop.is_set() and time.monotonic() - started < cfg["take_cap_s"]:
            texts += [recognizer.recognize(slicer.samples[a:b]) for a, b in slicer.feed(tail.read())]
            stop.wait(POLL_S)
        recorder.send_signal(signal.SIGINT)
        recorder.wait()
        if cancelled.is_set():
            pcm.unlink(missing_ok=True)
            return
        slices = slicer.feed(tail.read()) + slicer.finish()
        asr_started = time.monotonic()
        texts += [recognizer.recognize(slicer.samples[a:b]) for a, b in slices]
        raw = join_slices(texts)
        asr_ms = int((time.monotonic() - asr_started) * 1000)
        del recognizer
        gc.collect()

        cleaned, clean_ms = "", 0
        if raw:
            result = Cleaner().clean(raw)
            cleaned, clean_ms = result.text, result.elapsed_ms
        audio_s = pcm_to_wav(pcm, DICTATION / f"{take_id}.wav")
        pcm.unlink()
        retain(DICTATION, KEEP_AUDIO_S)
        with LOG.open("a") as log:
            log.write(json.dumps({
                "id": take_id, "ts": datetime.now(timezone.utc).isoformat(timespec="seconds"),
                "audio_s": round(audio_s, 2), "asr_ms_after_stop": asr_ms, "clean_ms": clean_ms,
                "raw": raw, "cleaned": cleaned,
            }, ensure_ascii=False) + "\n")
        write_result({"status": "text", "text": cleaned})
    except Exception as err:
        recorder.kill()
        write_result({"status": "error", "error": f"{type(err).__name__}: {err}"})
        raise
    finally:
        PIDFILE.unlink(missing_ok=True)


def toggle() -> dict:
    pid = running_worker()
    if pid is None:
        # A take that hit the cap on its own is collected by the next press rather than lost.
        finished = take_result()
        if finished is not None:
            return finished
        take_id = datetime.now().strftime("%Y%m%dT%H%M%S-") + uuid.uuid4().hex[:6]
        with (DICTATION / "worker.err").open("a") as err:
            worker = subprocess.Popen(
                [sys.executable, "-m", "stt_button.dictate", "_work", take_id],
                cwd=config.REPO, start_new_session=True,
                stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=err,
            )
        PIDFILE.write_text(str(worker.pid))
        # Report "recording" only once audio is arriving, so the indicator means it is safe to talk;
        # starting the worker and parec takes about a second.
        pcm = DICTATION / f"{take_id}.pcm"
        deadline = time.monotonic() + START_TIMEOUT_S
        while time.monotonic() < deadline:
            if pcm.exists() and pcm.stat().st_size > 0:
                return {"status": "recording"}
            if worker.poll() is not None:
                break
            time.sleep(0.05)
        cancel()
        return {"status": "error", "error": "microphone did not start; see data/dictation/worker.err"}
    os.kill(pid, signal.SIGUSR1)
    deadline = time.monotonic() + RESULT_TIMEOUT_S
    while time.monotonic() < deadline:
        finished = take_result()
        if finished is not None:
            return finished
        if running_worker() is None and not RESULT.exists():
            return {"status": "error", "error": "worker exited without a result; see data/dictation/worker.err"}
        time.sleep(0.1)
    return {"status": "error", "error": "timed out waiting for the transcript"}


def cancel() -> dict:
    pid = running_worker()
    if pid is None:
        return {"status": "idle"}
    os.kill(pid, signal.SIGTERM)
    return {"status": "cancelled"}


def main(argv: list[str]) -> int:
    DICTATION.mkdir(parents=True, exist_ok=True)
    if argv[:1] == ["_work"]:
        work(argv[1])
        return 0
    commands = {"toggle": toggle, "cancel": cancel}
    if len(argv) != 1 or argv[0] not in commands:
        print(__doc__, file=sys.stderr)
        return 2
    print(json.dumps(commands[argv[0]](), ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
