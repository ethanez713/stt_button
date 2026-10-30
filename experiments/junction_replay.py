"""Words repeated or lost where a field take's slices join, measured on the takes the phone kept.

    android/pull_takes.sh
    STT_MODEL_DIR=~/models/stt_button pyrun python -m experiments.junction_replay [--settle] [--top N]

Each take's audio runs through the host's mirror of the phone's field take (`LiveTake` on the field
slicer) and is compared word by word with one plain decode of the whole recording, which has no
joins. --settle replays the stop's settle windows instead: cut only at the hard cut, never at a pause.
Decodes are cached under data/experiments/junction/, keyed by their audio.
"""

import argparse
import difflib
import hashlib
import json
import sys
from pathlib import Path

import numpy as np

from experiments import paths, wer
from experiments.phone_replay import FEED_S, PHONE, Uncleaned, read_pcm
from stt_button import config
from stt_button.asr import SAMPLE_RATE, Slicer

CACHE = paths.EXPERIMENTS / "junction" / "decodes.json"
SINCE = "2026-10-21T14:28"
MAX_S = 90.0


class CachedRecognizer:
    def __init__(self, recognizer, store: dict):
        self.recognizer = recognizer
        self.store = store

    def _key(self, *parts) -> str:
        digest = hashlib.sha1()
        for part in parts:
            digest.update(part.tobytes() if isinstance(part, np.ndarray) else repr(part).encode())
        return digest.hexdigest()

    def recognize(self, samples: np.ndarray) -> str:
        key = self._key("plain", samples)
        if key not in self.store:
            self.store[key] = self.recognizer.recognize(samples)
        return self.store[key]

    def recognize_after(self, audio: np.ndarray, junction_s: float) -> tuple[str, str]:
        key = self._key("after", audio, junction_s)
        if key not in self.store:
            self.store[key] = list(self.recognizer.recognize_after(audio, junction_s))
        return tuple(self.store[key])


def field_takes(lines: list[dict], takes: Path, since: str = SINCE) -> list[str]:
    """Ids of the takes since `since` that kept audio, recognized something, and ended in their field or were discarded."""
    seen: dict[str, dict] = {}
    for line in lines:
        take_id = line.get("id")
        if not take_id or line.get("ts", "") < since:
            continue
        row = seen.setdefault(take_id, {"asr": False, "ended": None})
        if line.get("event") == "job" and line.get("kind") == "asr":
            row["asr"] = True
        if line.get("event") == "insert" and row["ended"] is None:
            row["ended"] = "field" if line.get("insert_path") == "field" else "other"
        if line.get("event") == "discard" and row["ended"] is None:
            row["ended"] = "field"
    return [take_id for take_id, row in seen.items()
            if row["asr"] and row["ended"] == "field" and (takes / f"{take_id}.pcm").exists()]


def errors(heard: str, reference: str) -> dict:
    """Words `heard` has beyond the reference, words it lacks, and words it has in place of others."""
    a, b = wer.normalize(heard), wer.normalize(reference)
    counts = {"extra": 0, "missing": 0, "subst": 0, "words": len(b)}
    for tag, i1, i2, j1, j2 in difflib.SequenceMatcher(a=a, b=b, autojunk=False).get_opcodes():
        if tag == "delete":
            counts["extra"] += i2 - i1
        elif tag == "insert":
            counts["missing"] += j2 - j1
        elif tag == "replace":
            counts["subst"] += max(i2 - i1, j2 - j1)
    return counts


def slicer_for(cfg: dict, settle: bool) -> Slicer:
    if settle:
        return Slicer.for_settle(cfg)
    return Slicer.from_config_for_field(cfg)


def replay(recognizer, samples: np.ndarray, cfg: dict, settle: bool = False) -> str:
    if settle:
        from stt_button.live import settle as settle_take

        return settle_take(recognizer, samples, cfg)
    from stt_button.live import LiveTake

    take = LiveTake(recognizer, Uncleaned, cfg, slicer=slicer_for(cfg, False))
    piece = int(FEED_S * SAMPLE_RATE)
    for start in range(0, len(samples), piece):
        take.feed(samples[start:start + piece])
    take.stop()
    return take.raw


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dir", type=Path, default=PHONE, help="holds edits.jsonl and takes/")
    parser.add_argument("--since", default=SINCE, help="first take timestamp, UTC")
    parser.add_argument("--settle", action="store_true", help="replay the stop's settle windows")
    parser.add_argument("--top", type=int, default=10, help="takes with the most differences to print")
    args = parser.parse_args()
    takes = args.dir / "takes"
    ids = field_takes(paths.read_jsonl(args.dir / "edits.jsonl"), takes, args.since)
    if not ids:
        print(f"no field take since {args.since} has audio in {takes}")
        return 1
    from stt_button.asr import Recognizer

    cfg = config.load()
    store = json.loads(CACHE.read_text()) if CACHE.exists() else {}
    recognizer = CachedRecognizer(Recognizer(), store)
    totals = {"extra": 0, "missing": 0, "subst": 0, "words": 0}
    rows, skipped = [], 0
    try:
        for take_id in ids:
            samples = read_pcm(takes / f"{take_id}.pcm")
            if len(samples) > MAX_S * SAMPLE_RATE:
                skipped += 1
                continue
            reference = recognizer.recognize(samples)
            heard = replay(recognizer, samples, cfg, args.settle)
            counts = errors(heard, reference)
            for key in totals:
                totals[key] += counts[key]
            rows.append((counts["extra"] + counts["missing"] + counts["subst"], take_id, heard, reference))
    finally:
        CACHE.parent.mkdir(parents=True, exist_ok=True)
        CACHE.write_text(json.dumps(store))
    print(f"{len(rows)} takes ({skipped} over {MAX_S:.0f} s skipped), {'settle windows' if args.settle else 'field slices'}")
    print(f"extra {totals['extra']}  missing {totals['missing']}  subst {totals['subst']}  of {totals['words']} words")
    for diff, take_id, heard, reference in sorted(rows, reverse=True)[:args.top]:
        if diff:
            print(f"\n{take_id[:8]} {diff}\n  heard: {heard}\n  whole: {reference}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
