"""Whether the audio a take kept ends in speech.

    android/pull_takes.sh
    pyrun python -m experiments.take_tails [--dir data/phone]

A stop that clips the last word leaves the kept audio ending on speech. Each pcm under
takes/ is joined to edits.jsonl by take id. The last full 100 ms window on the slicer's
grid is speech when it clears that take's own speech threshold, the one `stt_button.asr.Slicer`
uses. Under 20 stop-triggered takes the count is too small to decide anything, and the
script says so.
"""

import argparse
import sys
from collections import defaultdict
from pathlib import Path

import numpy as np

from experiments import paths
from stt_button import asr, config

PHONE = paths.REPO / "data" / "phone"
ENDING_EVENTS = ("insert", "clipboard", "discard")
TRIGGER_ORDER = ("stop", "cap", "silence", "keyboard", "package", "mic")
STOP_TAKES_TO_DECIDE = 20


def read_pcm(path: Path) -> np.ndarray:
    data = path.read_bytes()
    return np.frombuffer(data[:len(data) // 2 * 2], dtype="<i2")


def ends_in_speech(pcm: np.ndarray, cfg: dict | None = None) -> bool:
    """The last full 100 ms window on the slicer's grid is speech for this take."""
    if cfg is None:
        cfg = config.load()
    samples = pcm.astype(np.float32) / 32768
    slicer = asr.Slicer.from_config(cfg)
    slicer.feed(samples)
    end = len(samples) // slicer.win * slicer.win
    return end > 0 and slicer.speech_before(end) == end


def _rank(trigger: str | None) -> tuple:
    if trigger in TRIGGER_ORDER:
        return (0, TRIGGER_ORDER.index(trigger))
    if trigger is None:
        return (2, "")
    return (1, str(trigger))


def report(directory: Path, cfg: dict | None = None) -> str:
    """Per trigger: how many kept takes, and how many of them end in speech."""
    if cfg is None:
        cfg = config.load()
    seen: set[str] = set()
    ending: dict[str, dict] = {}
    for line in paths.read_jsonl(directory / "edits.jsonl"):
        take_id = line.get("id")
        if not isinstance(take_id, str) or not take_id:
            continue
        seen.add(take_id)
        if line.get("event") in ENDING_EVENTS:
            ending[take_id] = line

    groups: dict[str | None, list[bool]] = defaultdict(list)
    unlogged = 0
    takes = directory / "takes"
    if takes.is_dir():
        for pcm in sorted(takes.glob("*.pcm")):
            if pcm.stem not in seen:
                unlogged += 1
                continue
            line = ending.get(pcm.stem)
            trigger = line.get("trigger") if line else None
            if not isinstance(trigger, str):
                trigger = None
            groups[trigger].append(ends_in_speech(read_pcm(pcm), cfg))

    rows = []
    for trigger in sorted(groups, key=_rank):
        flags = groups[trigger]
        name = "null" if trigger is None else trigger
        rows.append(f"{name}: {len(flags)} takes, {sum(flags)} ending in speech")
    rows.append(f"takes without a log line: {unlogged}")
    stop = len(groups.get("stop", ()))
    if stop < STOP_TAKES_TO_DECIDE:
        rows.append(f"fewer than {STOP_TAKES_TO_DECIDE} stop-triggered takes ({stop})")
    return "\n".join(rows) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dir", type=Path, default=PHONE, help="holds edits.jsonl and takes/")
    args = parser.parse_args()
    sys.stdout.write(report(args.dir))
    return 0


if __name__ == "__main__":
    sys.exit(main())
