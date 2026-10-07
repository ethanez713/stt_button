"""How much of what a take inserted the user left alone, from the edit log (plan 20).

    pyrun python -m experiments.corrections [data/phone/edits.jsonl]

A take counts when it has one `insert` line, an `edit` line and a located span. The span's words are
compared with the cleaned text: `untouched` (equal), `marks` (the same words, other punctuation or
case), `words` (at least half the cleaned words survive) or `gone`. The zero-edit rate is the share
`untouched`.
"""

import argparse
import difflib
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

from experiments import config_ids, paths

DEFAULT_EDITS = paths.REPO / "data" / "phone" / "edits.jsonl"
CLASSES = ("untouched", "marks", "words", "gone")
SURVIVING_SHARE = 0.5
WORD = re.compile(r"\w+(?:['’]\w+)*")


def words(text: str) -> list[str]:
    return [w.lower() for w in WORD.findall(text)]


def _in_word(char: str) -> bool:
    return char.isalnum() or char in "'’"


def widen(text: str, start: int, end: int) -> tuple[int, int]:
    """The span grown to whole words, so a letter typed against its edge is part of the word it changes."""
    while 0 < start < end and _in_word(text[start - 1]) and _in_word(text[start]):
        start -= 1
    while start < end < len(text) and _in_word(text[end - 1]) and _in_word(text[end]):
        end += 1
    return start, end


def classify(cleaned: str, text_after: str, span_start: int, span_end: int) -> tuple[str, list[tuple[list[str], list[str]]], str]:
    """The class of a take, its word runs as (cleaned words, words now) pairs (empty unless `words`), and the span's text now."""
    start, end = widen(text_after, min(span_start, len(text_after)), min(span_end, len(text_after)))
    now = text_after[start:end]
    if now.strip() == cleaned.strip():
        return "untouched", [], now
    before, after = words(cleaned), words(now)
    if before == after:
        return "marks", [], now
    matcher = difflib.SequenceMatcher(a=before, b=after, autojunk=False)
    kept = sum(block.size for block in matcher.get_matching_blocks())
    if start == end or kept < SURVIVING_SHARE * len(before):
        return "gone", [], now
    runs = [(before[i1:i2], after[j1:j2]) for tag, i1, i2, j1, j2 in matcher.get_opcodes() if tag != "equal"]
    return "words", runs, now


def analyse(lines: list[dict]) -> tuple[list[dict], Counter]:
    """Counted takes as {id, ts, pkg, clean_mode, cleaned, text_before, reference, class, runs}, and how many inserted takes were not counted, by reason."""
    inserts, edits = defaultdict(list), {}
    for line in lines:
        if line.get("event") == "insert":
            inserts[line["id"]].append(line)
        elif line.get("event") == "edit":
            edits[line["id"]] = line
    counted, skipped = [], Counter()
    for take_id, rows in inserts.items():
        edit = edits.get(take_id)
        if len(rows) > 1:
            skipped["several inserts"] += 1
        elif edit is None:
            skipped["no edit line"] += 1
        elif edit.get("span_start") is None or edit.get("span_end") is None:
            skipped["null span"] += 1
        else:
            insert = rows[0]
            cls, runs, reference = classify(insert["cleaned"], edit["text_after"], edit["span_start"], edit["span_end"])
            counted.append({"id": take_id, "ts": insert.get("ts"), "pkg": insert.get("pkg"), "clean_mode": insert.get("clean_mode"),
                            "cleaned": insert["cleaned"], "text_before": insert.get("text_before") or "",
                            "reference": reference.strip(), "class": cls, "runs": runs})
    return counted, skipped


def _shares(rows: list[dict]) -> str:
    counts = Counter(r["class"] for r in rows)
    return "  ".join(f"{c} {counts[c]} ({counts[c] / len(rows) * 100:.0f}%)" for c in CLASSES)


def _by(rows: list[dict], key: str) -> list[str]:
    groups = defaultdict(list)
    for r in rows:
        groups[r[key]].append(r)
    return [f"  {name}: {len(group)} takes — {_shares(group)}" for name, group in sorted(groups.items(), key=lambda kv: str(kv[0]))]


def report(lines: list[dict]) -> str:
    counted, skipped = analyse(lines)
    out = [f"counted takes: {len(counted)}"]
    if counted:
        untouched = sum(r["class"] == "untouched" for r in counted)
        out += [_shares(counted), f"zero-edit rate: {untouched / len(counted) * 100:.0f}% ({untouched} of {len(counted)})",
                "\nby package:", *_by(counted, "pkg"), "\nby clean_mode:", *_by(counted, "clean_mode")]
    replaced = [r for r in counted if r["class"] == "words"]
    if replaced:
        out.append("\nreplaced words (cleaned → now):")
        for r in replaced:
            for before, after in r["runs"]:
                out.append(f"  {r['id']}: {' '.join(before) or '∅'} → {' '.join(after) or '∅'}")
    out.append(f"\nnot counted: {sum(skipped.values())}" + "".join(f"\n  {reason}: {n}" for reason, n in sorted(skipped.items())))
    return "\n".join(out)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("edits", type=Path, nargs="?", default=DEFAULT_EDITS)
    parser.add_argument("--all-configs", action="store_true", help="pool takes whose configurations differ")
    args = parser.parse_args(argv)
    lines = paths.read_jsonl(args.edits)
    header, ok = config_ids.check(lines, args.all_configs)
    print(header)
    if not ok:
        return 1
    print(report(lines))
    return 0


if __name__ == "__main__":
    sys.exit(main())
