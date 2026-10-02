"""Count the cleaned takes in which the cleaner swapped a name or number for another.

    adb exec-out run-as com.ezhou.sttbutton cat files/logs/edits.jsonl > data/phone/edits.jsonl
    pyrun python -m experiments.fact_drift [data/dictation/log.jsonl data/phone/edits.jsonl]

Reads desktop take lines (clean_mode "chunks", or no mode with changed text) and phone insert lines
(clean_mode "chunks"), and prints each flagged raw/cleaned pair for hand marking.
"""

import json
import sys
from pathlib import Path

from stt_button import config
from stt_button.cleaner import fact_drift

DEFAULT_LOGS = [config.REPO / "data" / "dictation" / "log.jsonl", config.REPO / "data" / "phone" / "edits.jsonl"]


def cleaned_pairs(path: Path) -> list[tuple[str, str, str]]:
    pairs = []
    for line in path.read_text().splitlines():
        row = json.loads(line)
        if row.get("event", "insert") != "insert":
            continue
        mode = row.get("clean_mode")
        if mode == "chunks" or (mode is None and row["raw"] != row["cleaned"]):
            pairs.append((str(row.get("id", ""))[:20], row["raw"], row["cleaned"]))
    return pairs


def main(argv: list[str]) -> int:
    logs = [Path(a) for a in argv] or DEFAULT_LOGS
    for log in logs:
        pairs = cleaned_pairs(log)
        flagged = [(i, raw, cleaned, fact_drift(raw, cleaned)) for i, raw, cleaned in pairs]
        flagged = [f for f in flagged if f[3]]
        print(f"{log}: {len(flagged)} flagged of {len(pairs)} cleaned")
        for take_id, raw, cleaned, pairs_found in flagged:
            print(f"  {take_id}: {pairs_found}")
            print(f"    raw: {raw}\n    cleaned: {cleaned}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
