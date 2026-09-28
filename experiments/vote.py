"""Blind A/B vote between the two cleaners' outputs; resumable.

    pyrun python -m experiments.vote
"""

import json
import random
import sys
from pathlib import Path

from experiments import paths

ARMS = ("s1-mini", "qwen3.5-2b")
SEED = 20260927


def build_key(ids: list[str], seed: int = SEED) -> dict:
    """Presentation order and a random A/B assignment per ramble."""
    rng = random.Random(seed)
    order = sorted(ids)
    rng.shuffle(order)
    return {"order": order, "labels": {i: dict(zip("AB", rng.sample(ARMS, 2))) for i in order}}


def load_or_create_key(ids: list[str], path: Path = paths.BLIND_KEY) -> dict:
    if path.exists():
        key = json.loads(path.read_text())
        if set(key["order"]) == set(ids):
            return key
        # New rambles since the key was made: extend it without reshuffling what was already shown.
        extra = build_key([i for i in ids if i not in key["labels"]])
        key["order"] += extra["order"]
        key["labels"] |= extra["labels"]
    else:
        key = build_key(ids)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(key, indent=1) + "\n")
    return key


def pending(key: dict, votes: list[dict]) -> list[str]:
    voted = {v["id"] for v in votes}
    return [i for i in key["order"] if i not in voted]


def ask(question: str, answers: str) -> str:
    while True:
        reply = input(f"{question} [{'/'.join(answers)}] ").strip().lower()
        if reply in answers:
            return reply


def main() -> int:
    outputs = {arm: paths.CLEAN / arm for arm in ARMS}
    ids = sorted(p.stem for p in outputs[ARMS[0]].glob("*.txt") if (outputs[ARMS[1]] / p.name).exists())
    if not ids:
        sys.exit("no cleaned rambles from both arms; run experiments.clean_bakeoff first")
    key = load_or_create_key(ids)
    todo = pending(key, paths.read_jsonl(paths.VOTES))
    recognizer_by_id = {r["id"]: r["recognizer"] for r in paths.read_jsonl(paths.CLEAN_RESULTS)}

    for n, i in enumerate(todo, 1):
        raw_path = paths.ASR / recognizer_by_id.get(i, "") / f"{i}.txt"
        raw = raw_path.read_text().strip() if raw_path.is_file() else "(raw transcript not found)"
        labels = key["labels"][i]
        print(f"\n===== {n}/{len(todo)}  {i} =====\n\nRAW:\n{raw}\n")
        for letter in "AB":
            print(f"--- {letter} ---\n{(outputs[labels[letter]] / f'{i}.txt').read_text().strip()}\n")
        choice = ask("Which would you rather have inserted?", "abt")
        usable = None if choice == "t" else ask(f"Is {choice.upper()} usable without edits?", "yn") == "y"
        paths.append_jsonl(paths.VOTES, {"id": i, "choice": choice, "usable": usable})
    print("All rambles voted.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
