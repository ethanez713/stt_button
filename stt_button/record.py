"""Record one dictation take from this machine's microphone for the host experiments.

    pyrun python -m stt_button.record --mode read|ramble|short [--prompt ID]
"""

import argparse
import array
import json
import math
import select
import signal
import subprocess
import sys
import time
import wave
from datetime import datetime, timezone
from pathlib import Path

from stt_button import config

RECORDINGS = config.REPO / "data" / "recordings"
PROMPTS = config.REPO / "experiments" / "prompts.json"
SAMPLE_RATE = 16000
SILENT_DBFS = -50.0
# A blocked or muted mic delivers digital zeros; this stands in for -inf so the sidecar stays valid JSON.
DBFS_FLOOR = -120.0
WAV_HEADER_BYTES = 44
LABELS = {
    "read": "Read this aloud",
    "ramble": "Talk about this, unplanned, 1-5 minutes",
    "short": "Say this the way you'd dictate it",
}


def load_prompts(path: Path = PROMPTS) -> dict:
    return json.loads(path.read_text())


def recorded_prompt_ids(recordings: Path) -> set[str]:
    return {json.loads(p.read_text())["prompt_id"] for p in recordings.glob("*.json")}


def next_prompt(mode: str, prompts: dict, recordings: Path) -> dict | None:
    done = recorded_prompt_ids(recordings)
    return next((p for p in prompts[mode] if p["id"] not in done), None)


def wav_stats(path: Path) -> tuple[float, float]:
    """Duration in seconds and RMS level in dBFS; rejects anything but 16 kHz mono PCM16."""
    with wave.open(str(path)) as w:
        shape = (w.getframerate(), w.getnchannels(), w.getsampwidth())
        if shape != (SAMPLE_RATE, 1, 2):
            raise ValueError(f"{path}: expected 16000 Hz mono 16-bit, got {shape}")
        samples = array.array("h", w.readframes(w.getnframes()))
    duration = len(samples) / SAMPLE_RATE
    if not samples:
        return duration, DBFS_FLOOR
    rms = math.sqrt(sum(s * s for s in samples) / len(samples))
    return duration, max(DBFS_FLOOR, 20 * math.log10(rms / 32768)) if rms else DBFS_FLOOR


def write_sidecar(wav: Path, prompt: dict, mode: str, ts: datetime) -> dict:
    duration, dbfs = wav_stats(wav)
    meta = {
        "id": wav.stem,
        "prompt_id": prompt["id"],
        "mode": mode,
        "ts": ts.isoformat(timespec="seconds"),
        "duration_s": round(duration, 2),
        "rms_dbfs": round(dbfs, 1),
    }
    if mode == "read":
        meta["reference"] = prompt["text"]
    wav.with_suffix(".json").write_text(json.dumps(meta, indent=1) + "\n")
    return meta


def _enter_pressed(timeout_s: float) -> bool:
    ready, _, _ = select.select([sys.stdin], [], [], timeout_s)
    if ready:
        sys.stdin.readline()
    return bool(ready)


def capture(wav: Path, cap_s: int) -> None:
    proc = subprocess.Popen(
        ["parecord", "--device=RDPSource", f"--rate={SAMPLE_RATE}", "--channels=1",
         "--format=s16le", "--file-format=wav", str(wav)],
    )
    # parecord takes most of a second to start delivering audio; announce only once it has.
    while not (wav.exists() and wav.stat().st_size > WAV_HEADER_BYTES):
        if proc.poll() is not None:
            sys.exit(f"parecord exited with {proc.returncode} before recording started")
        time.sleep(0.05)
    print(f"Recording. Speak now; press Enter to stop (auto-stop at {cap_s // 60}:{cap_s % 60:02d}).")
    started = time.monotonic()
    while time.monotonic() - started < cap_s and proc.poll() is None:
        if _enter_pressed(0.25):
            break
    # SIGINT, not SIGTERM: parecord finalizes the wav header on SIGINT.
    proc.send_signal(signal.SIGINT)
    proc.wait()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--mode", choices=list(LABELS), required=True)
    parser.add_argument("--prompt", help="prompt id to (re)record; default is the next unrecorded one")
    args = parser.parse_args(argv)

    prompts = load_prompts()
    RECORDINGS.mkdir(parents=True, exist_ok=True)
    if args.prompt:
        prompt = next((p for p in prompts[args.mode] if p["id"] == args.prompt), None)
        if prompt is None:
            sys.exit(f"no {args.mode} prompt {args.prompt!r}")
    else:
        prompt = next_prompt(args.mode, prompts, RECORDINGS)
        if prompt is None:
            print(f"Every {args.mode} prompt is recorded. Pass --prompt ID to record one again.")
            return 0

    print(f"\n[{prompt['id']}] {LABELS[args.mode]}:\n\n{prompt['text']}\n")
    input("Press Enter to start.")

    ts = datetime.now(timezone.utc)
    wav = RECORDINGS / f"{prompt['id']}-{ts:%Y%m%dT%H%M%S}.wav"
    try:
        capture(wav, config.load()["take_cap_s"])
    except KeyboardInterrupt:
        # The terminal's Ctrl-C reaches parecord too; let it finish writing before deleting.
        time.sleep(0.5)
        wav.unlink(missing_ok=True)
        print(f"\nDiscarded the partial take. [{prompt['id']}] is still unrecorded.")
        return 130
    meta = write_sidecar(wav, prompt, args.mode, ts)

    done = len(recorded_prompt_ids(RECORDINGS) & {p["id"] for p in prompts[args.mode]})
    print(f"Saved {wav.name}: {meta['duration_s']} s, {meta['rms_dbfs']} dBFS. "
          f"{done} of {len(prompts[args.mode])} {args.mode} prompts recorded.")
    if meta["rms_dbfs"] < SILENT_DBFS:
        print("mic looks silent")
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
