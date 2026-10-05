"""What the host recognizer hears in the takes the phone kept, beside what the phone logged.

    android/pull_takes.sh
    STT_MODEL_DIR=~/models/stt_button pyrun python -m experiments.phone_replay [--wav] [--last N]

Each take's audio is run through the host's slicing and recognizer. Words the phone logged and the host
does not hear, or the reverse, point at the phone's pipeline; words both got wrong are the recognizer's.
--wav also writes each take as a wav beside its pcm, to listen to.
"""

import argparse
import difflib
import sys
from pathlib import Path

import numpy as np

from experiments import paths, wer
from stt_button import config
from stt_button.asr import SAMPLE_RATE

PHONE = paths.REPO / "data" / "phone"
FEED_S = 0.1


class Uncleaned:
    """Stands in for the cleaner: the replay compares recognized text only."""

    def count_tokens(self, text: str) -> int:
        return len(text.split())

    def clean_chunk(self, chunk: str, cancelled=None) -> tuple[str, int]:
        return chunk, 0


def read_pcm(path: Path) -> np.ndarray:
    data = path.read_bytes()
    return np.frombuffer(data[:len(data) // 2 * 2], dtype="<i2").astype(np.float32) / 32768


def logged_takes(lines: list[dict], takes: Path) -> list[dict]:
    """One row per take that has audio, oldest first: its id, start time, what ended it, and the raw text of its lines."""
    rows: dict[str, dict] = {}
    for line in lines:
        take_id = line.get("id")
        if not take_id or not (takes / f"{take_id}.pcm").exists():
            continue
        row = rows.setdefault(take_id, {"id": take_id, "ts": line.get("ts"), "ended": None, "raw": []})
        if line.get("event") in ("insert", "clipboard", "discard", "end"):
            row["ended"] = f"{line['event']}:{line.get('trigger') or line.get('reason')}"
        if line.get("raw"):
            row["raw"].append(line["raw"])
    return [{**row, "raw": " ".join(row["raw"])} for row in rows.values()]


def word_diff(logged: str, replayed: str) -> list[tuple[str, str]]:
    """(words only the phone logged, words only the host heard) at each place the two differ."""
    a, b = wer.normalize(logged), wer.normalize(replayed)
    matcher = difflib.SequenceMatcher(a=a, b=b, autojunk=False)
    return [(" ".join(a[i1:i2]), " ".join(b[j1:j2])) for tag, i1, i2, j1, j2 in matcher.get_opcodes() if tag != "equal"]


def replay(recognizer, samples: np.ndarray, cfg: dict) -> str:
    from stt_button.live import LiveTake

    take = LiveTake(recognizer, Uncleaned, cfg)
    piece = int(FEED_S * SAMPLE_RATE)
    for start in range(0, len(samples), piece):
        take.feed(samples[start:start + piece])
    return take.finish().raw


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dir", type=Path, default=PHONE, help="holds edits.jsonl and takes/")
    parser.add_argument("--last", type=int, help="only the newest N takes")
    parser.add_argument("--wav", action="store_true")
    args = parser.parse_args()
    takes = args.dir / "takes"
    rows = logged_takes(paths.read_jsonl(args.dir / "edits.jsonl"), takes)
    if args.last:
        rows = rows[-args.last:]
    if not rows:
        print(f"no take in {args.dir / 'edits.jsonl'} has audio in {takes}")
        return 1
    from stt_button.asr import Recognizer
    from stt_button.dictate import pcm_to_wav

    cfg = config.load()
    recognizer = Recognizer()
    for row in rows:
        pcm = takes / f"{row['id']}.pcm"
        if args.wav:
            pcm_to_wav(pcm, pcm.with_suffix(".wav"))
        samples = read_pcm(pcm)
        heard = replay(recognizer, samples, cfg)
        print(f"\n{row['ts']} {row['id'][:8]} {len(samples) / SAMPLE_RATE:.1f} s ended={row['ended']}")
        print(f"  phone: {row['raw']}")
        print(f"  host:  {heard}")
        for phone_only, host_only in word_diff(row["raw"], heard):
            print(f"  differs: phone {phone_only!r} / host {host_only!r}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
