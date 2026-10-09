"""WSL half of hotkey dictation. The Windows helper records the take; this cleans it up.

    python -m stt_button.dictate start <pcm path>   # spawn a worker that follows a growing take
    python -m stt_button.dictate stop               # ask the running worker to finish; print the text
    python -m stt_button.dictate cancel             # ask the running worker to discard the take

Stop, cancel and the result are files beside that take's pcm (<id>.stop, <id>.cancel,
<id>.result.json). A second start for a pcm whose worker is already running reports started.

Each command prints one JSON object: {"status": "started"}, {"status": "text", "text": ...},
{"status": "cancelled"}, {"status": "idle"}, or {"status": "error", "error": ...}.

The CLI imports nothing heavy, because the stop path is on the user's wait; numpy and the models
load only in the worker, which starts while the user is still talking.
"""

import json
import os
import subprocess
import sys
import time
import wave
from datetime import datetime, timezone
from pathlib import Path

from stt_button import config

DICTATION = config.REPO / "data" / "dictation"
PIDFILE = DICTATION / "worker.pid"
LOG = DICTATION / "log.jsonl"
SAMPLE_RATE = 16000
KEEP_AUDIO_S = 600
POLL_S = 0.05
RESULT_TIMEOUT_S = 600
# Windows refuses the rename while the helper has the result open; a short gap is enough.
RESULT_REPLACE_ATTEMPTS = 50
RESULT_REPLACE_PAUSE_S = 0.02
# The helper enforces the take cap; this only stops a worker whose helper has gone away.
ORPHAN_GRACE_S = 30


def take_files(pcm: Path) -> tuple[Path, Path, Path]:
    """(stop flag, cancel flag, result) beside the pcm."""
    return pcm.with_suffix(".stop"), pcm.with_suffix(".cancel"), pcm.with_suffix(".result.json")


def write_result(result: dict, path: Path) -> None:
    partial = path.with_suffix(".tmp")
    partial.write_text(json.dumps(result, ensure_ascii=False))
    # Windows refuses the rename while the helper has the file open for a read.
    for attempt in range(RESULT_REPLACE_ATTEMPTS):
        try:
            partial.replace(path)
            return
        except PermissionError:
            if attempt == RESULT_REPLACE_ATTEMPTS - 1:
                raise
            time.sleep(RESULT_REPLACE_PAUSE_S)


def take_result(path: Path) -> dict | None:
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
    data = data[:len(data) // 2 * 2]
    with wave.open(str(wav), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SAMPLE_RATE)
        w.writeframes(data)
    return len(data) / 2 / SAMPLE_RATE


class PcmTail:
    """Reads the whole samples appended to a raw s16le file since the last read."""

    def __init__(self, path: Path):
        self.path = path
        self.offset = 0

    def read(self):
        import numpy as np

        try:
            with self.path.open("rb") as f:
                f.seek(self.offset)
                data = f.read()
        except FileNotFoundError:
            return np.zeros(0, dtype=np.float32)
        data = data[:len(data) // 2 * 2]
        self.offset += len(data)
        return np.frombuffer(data, dtype="<i2").astype(np.float32) / 32768


def release_take(pid: int, pidfile: Path = PIDFILE, flags: tuple[Path, ...] = ()) -> None:
    """Mark the take closed, unless a newer take has already claimed the pidfile."""
    try:
        if int(pidfile.read_text()) != pid:
            return
    except (FileNotFoundError, ValueError):
        pass
    for path in (pidfile, *flags):
        path.unlink(missing_ok=True)


def stop_requested_at(flag: Path) -> float:
    """When the stop was asked for, not when the worker noticed: an idle clean in progress delays the noticing."""
    try:
        return flag.stat().st_mtime
    except FileNotFoundError:
        return time.time()


def pcm_from_cmdline(cmdline: bytes) -> Path | None:
    args = cmdline.split(b"\0")
    try:
        work = args.index(b"_work")
    except ValueError:
        return None
    if work + 1 >= len(args) or not args[work + 1]:
        return None
    return Path(os.fsdecode(args[work + 1]))


def running_pcm(pidfile: Path = PIDFILE) -> Path | None:
    """The pcm of the worker named by the pidfile, if that process is a dictate worker."""
    pid = running_worker(pidfile)
    if pid is None:
        return None
    try:
        return pcm_from_cmdline(Path(f"/proc/{pid}/cmdline").read_bytes())
    except FileNotFoundError:
        return None


def work(pcm: Path) -> None:
    import threading

    from stt_button.asr import Recognizer
    from stt_button.bubble import SharedBubble
    from stt_button.cleaner import Cleaner
    from stt_button.live import LiveTake
    from stt_button.preview import Preview

    cfg = config.load()
    take_id = pcm.stem
    stop_flag, cancel_flag, result_path = take_files(pcm)
    flags = (stop_flag, cancel_flag)
    loaded: dict = {}

    def halted() -> bool:
        return stop_flag.exists() or cancel_flag.exists()

    def load_cleaner():
        # The GPU through Ollama is ~4x the CPU here when the laptop is in its performance mode;
        # the in-process CPU backend covers Ollama being down.
        try:
            try:
                loaded["model"] = Cleaner(backend="ollama")
            except OSError as err:
                print(f"Ollama unavailable ({err}); cleaning on the CPU", file=sys.stderr)
                loaded["model"] = Cleaner()
        except Exception as err:
            loaded["error"] = err

    loading = threading.Thread(target=load_cleaner, daemon=True)
    loading.start()

    def get_cleaner():
        loading.join()
        if "error" in loaded:
            raise loaded["error"]
        return loaded["model"]

    def release_cleaner():
        loading.join()
        model = loaded.pop("model", None)
        if model is not None:
            loaded["backend"] = model.backend
            model.release()

    published = False
    try:
        bubble = SharedBubble(pcm.with_suffix(".bubble.json"))
        Preview(bubble, PcmTail(pcm).read, halted).start()
        live = LiveTake(Recognizer(), get_cleaner, cfg, bubble)
        tail = PcmTail(pcm)
        deadline = time.monotonic() + cfg["take_cap_s"] + ORPHAN_GRACE_S
        while not halted() and time.monotonic() < deadline:
            live.feed(tail.read())
            if not live.idle_step(tail.read, halted):
                time.sleep(POLL_S)
        stopped = stop_requested_at(stop_flag)
        if cancel_flag.exists():
            pcm.unlink(missing_ok=True)
            bubble.path.unlink(missing_ok=True)
            return
        live.mark_stop()
        live.feed(tail.read())
        result = live.finish()
        bubble.flush()
        # Result, then the pidfile, then the cleaner. Unloading joins a load this take may not
        # have used, and the paste must not wait on it; the unload can hit the next take's model.
        write_result({"status": "text", "text": result.cleaned}, result_path)
        published = True
        stop_to_result_ms = int((time.time() - stopped) * 1000)
        release_take(os.getpid(), flags=flags)
        release_cleaner()
        bubble.path.unlink(missing_ok=True)

        audio_s = pcm_to_wav(pcm, DICTATION / f"{take_id}.wav")
        pcm.unlink()
        retain(DICTATION, KEEP_AUDIO_S)
        with LOG.open("a") as log:
            log.write(json.dumps({
                "id": take_id, "ts": datetime.now(timezone.utc).isoformat(timespec="seconds"),
                "audio_s": round(audio_s, 2), "asr_ms_after_stop": result.asr_ms_after_stop,
                "clean_ms": live.clean_ms, "clean_ms_after_stop": result.clean_ms_after_stop,
                "stop_to_result_ms": stop_to_result_ms, "clean_mode": result.clean_mode, "chunks": result.chunks,
                "idle_cleans": result.idle_cleans, "idle_hits": result.idle_hits, "cue": result.cue,
                "context_decodes": result.context_decodes, "context_fallbacks": result.context_fallbacks,
                "drifts": result.drifts, "corrections": result.corrections, "corrections_kept": result.corrections_kept,
                "cleaner_backend": loaded.get("backend"),
                "raw": result.raw, "cleaned": result.cleaned,
            }, ensure_ascii=False) + "\n")
    except Exception as err:
        if not published:
            write_result({"status": "error", "error": f"{type(err).__name__}: {err}"}, result_path)
        raise
    finally:
        # Pidfile before the unload, so the next take is not stuck behind this process's model.
        release_take(os.getpid(), flags=flags)
        release_cleaner()


def start_decision(requested: Path, running: Path | None) -> dict | None:
    """What start returns without spawning, or None when it should spawn a worker.

    A retry can follow a launch whose reply never arrived, and that worker is already on this pcm.
    """
    if running is None:
        return None
    if Path(running) == Path(requested):
        return {"status": "started"}
    return {"status": "error", "error": "a take is already open"}


def start(pcm: str) -> dict:
    requested = Path(pcm)
    decision = start_decision(requested, running_pcm())
    if decision is not None:
        return decision
    # Leave a stop or cancel that is already beside the pcm: the worker has not seen it yet.
    with (DICTATION / "worker.err").open("a") as err:
        worker = subprocess.Popen(
            [sys.executable, "-m", "stt_button.dictate", "_work", pcm],
            cwd=config.REPO, start_new_session=True,
            stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=err,
        )
    PIDFILE.write_text(str(worker.pid))
    return {"status": "started"}


def stop() -> dict:
    pcm = running_pcm()
    if pcm is None:
        return {"status": "error", "error": "no take was open; see data/dictation/worker.err"}
    stop_flag, _, result_path = take_files(pcm)
    stop_flag.touch()
    deadline = time.monotonic() + RESULT_TIMEOUT_S
    while time.monotonic() < deadline:
        finished = take_result(result_path)
        if finished is not None:
            return finished
        if running_pcm() is None and not result_path.exists():
            stop_flag.unlink(missing_ok=True)
            return {"status": "error", "error": "no take was open; see data/dictation/worker.err"}
        time.sleep(POLL_S)
    return {"status": "error", "error": "timed out waiting for the transcript"}


def cancel() -> dict:
    pcm = running_pcm()
    if pcm is None:
        return {"status": "idle"}
    _, cancel_flag, _ = take_files(pcm)
    cancel_flag.touch()
    return {"status": "cancelled"}


def main(argv: list[str]) -> int:
    DICTATION.mkdir(parents=True, exist_ok=True)
    if argv[:1] == ["_work"] and len(argv) == 2:
        work(Path(argv[1]))
        return 0
    if argv[:1] == ["start"] and len(argv) == 2:
        result = start(argv[1])
    elif argv in (["stop"], ["cancel"]):
        result = stop() if argv == ["stop"] else cancel()
    else:
        print(__doc__, file=sys.stderr)
        return 2
    print(json.dumps(result, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
