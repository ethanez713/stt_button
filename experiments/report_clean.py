"""Unblind the cleaner votes and apply the phase 04 decision rule.

    pyrun python -m experiments.report_clean
"""

import json
import statistics
import sys

from experiments import paths
from experiments.vote import ARMS

MIN_RAMBLES = 10
SWITCH_SHARE = 2 / 3


def tally(votes: list[dict], key: dict) -> dict:
    wins = {arm: 0 for arm in ARMS}
    usable = {arm: 0 for arm in ARMS}
    ties = unjoined = 0
    for v in votes:
        labels = key["labels"].get(v["id"])
        if labels is None:
            unjoined += 1
        elif v["choice"] == "t":
            ties += 1
        else:
            arm = labels[v["choice"].upper()]
            wins[arm] += 1
            usable[arm] += bool(v["usable"])
    return {"wins": wins, "usable": usable, "ties": ties, "unjoined": unjoined}


def verdict(t: dict) -> str:
    decided = sum(t["wins"].values())
    if decided + t["ties"] < MIN_RAMBLES:
        return f"undecided: {decided + t['ties']} votes, the rule needs at least {MIN_RAMBLES}"
    share = t["wins"]["qwen3.5-2b"] / decided if decided else 0
    if share >= SWITCH_SHARE:
        return f"switch to qwen3.5-2b (won {share:.0%} of non-tie votes)"
    return f"keep s1-mini (qwen3.5-2b won {share:.0%} of non-tie votes; switching needs {SWITCH_SHARE:.0%})"


def main() -> int:
    if not paths.BLIND_KEY.exists():
        sys.exit("no blind key; run experiments.vote first")
    key = json.loads(paths.BLIND_KEY.read_text())
    t = tally(paths.read_jsonl(paths.VOTES), key)
    results = paths.read_jsonl(paths.CLEAN_RESULTS)

    for arm in ARMS:
        rows = [r for r in results if r["arm"] == arm]
        per_k = [1000 * r["elapsed_ms"] / r["in_tokens"] for r in rows if r["in_tokens"]]
        backends = sorted({r["backend"] for r in rows})
        speed = f"{statistics.median(per_k) / 1000:.1f} s per 1000 input tokens" if per_k else "no timings"
        print(f"{arm:11} wins {t['wins'][arm]:>2}  usable-as-is {t['usable'][arm]:>2}  {speed}  backend {backends}")
    print(f"ties {t['ties']}, unjoined votes {t['unjoined']} (should be 0)")
    if results:
        biggest = max(results, key=lambda r: r["in_tokens"])
        print(f"largest transcript: {biggest['in_tokens']} tokens ({biggest['id']}), "
              f"max chunks {max(r['chunks'] for r in results)}")
    print(f"\nverdict: {verdict(t)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
