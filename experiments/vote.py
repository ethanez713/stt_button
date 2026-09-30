"""Blind A/B vote between two output directories; resumable.

    pyrun python -m experiments.vote
    pyrun python -m experiments.vote --a DIR --b DIR --votes FILE

With no arguments this votes s1-mini against qwen3.5-2b into paths.VOTES, keyed by paths.BLIND_KEY.
"""

import argparse
import json
import random
import sys
from pathlib import Path

from experiments import paths

ARMS = ("s1-mini", "qwen3.5-2b")
SEED = 20260927


def argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--a", type=Path, help="output directory shown against --b")
    parser.add_argument("--b", type=Path, help="output directory shown against --a")
    parser.add_argument("--votes", type=Path, help="votes jsonl; blind key is <stem>_key.json beside it")
    return parser


def build_key(ids: list[str], seed: int = SEED, arms: tuple[str, str] = ARMS) -> dict:
    """Presentation order and a random A/B assignment per id."""
    rng = random.Random(seed)
    order = sorted(ids)
    rng.shuffle(order)
    return {"order": order, "labels": {i: dict(zip("AB", rng.sample(arms, 2))) for i in order}}


def load_or_create_key(ids: list[str], path: Path = paths.BLIND_KEY, arms: tuple[str, str] = ARMS) -> dict:
    if path.exists():
        key = json.loads(path.read_text())
        if set(key["order"]) == set(ids):
            return key
        # New ids since the key was made: extend it without reshuffling what was already shown.
        extra = build_key([i for i in ids if i not in key["labels"]], arms=arms)
        key["order"] += extra["order"]
        key["labels"] |= extra["labels"]
    else:
        key = build_key(ids, arms=arms)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(key, indent=1) + "\n")
    return key


def resolve_vote(
    a: Path | None = None, b: Path | None = None, votes: Path | None = None,
) -> tuple[dict[str, Path], Path, Path]:
    """Output dirs keyed by the label stored in the blind key, plus the votes file and that key."""
    if a is None and b is None and votes is None:
        return {arm: paths.CLEAN / arm for arm in ARMS}, paths.VOTES, paths.BLIND_KEY
    if a is None or b is None or votes is None:
        sys.exit("--a, --b and --votes must be given together")
    a, b, votes = a.resolve(), b.resolve(), votes.resolve()
    return {str(a): a, str(b): b}, votes, votes.with_name(votes.stem + "_key.json")


def pending(key: dict, votes: list[dict]) -> list[str]:
    voted = {v["id"] for v in votes}
    return [i for i in key["order"] if i not in voted]


def ask(question: str, answers: str) -> str:
    while True:
        reply = input(f"{question} [{'/'.join(answers)}] ").strip().lower()
        if reply in answers:
            return reply


def main() -> int:
    args = argument_parser().parse_args()
    outputs, votes_path, key_path = resolve_vote(args.a, args.b, args.votes)
    arm_a, arm_b = tuple(outputs)
    ids = sorted(p.stem for p in outputs[arm_a].glob("*.txt") if (outputs[arm_b] / p.name).exists())
    if not ids:
        if args.a is None:
            sys.exit("no cleaned rambles from both arms; run experiments.clean_bakeoff first")
        sys.exit(f"no transcripts in both {args.a} and {args.b}")
    key = load_or_create_key(ids, key_path, (arm_a, arm_b))
    todo = pending(key, paths.read_jsonl(votes_path))
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
        paths.append_jsonl(votes_path, {"id": i, "choice": choice, "usable": usable})
    print("All rambles voted.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
