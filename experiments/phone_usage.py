"""What dictation costs on the phone, from the edit log the app writes (plan 07).

    adb exec-out run-as com.ezhou.sttbutton cat files/logs/edits.jsonl > edits.jsonl
    pyrun python -m experiments.phone_usage edits.jsonl [--capacity-mah 5000]

Plugged takes are excluded: charging current hides the take's draw. Medians and counts only; the
figures do not claim precision.
"""

import argparse
import statistics
import sys
from collections import Counter, defaultdict
from pathlib import Path

from experiments import paths

COST_FIELDS = ["charge_uah", "counter_used_uah", "plugged", "battery_pct", "peak_pss_kb", "avail_mem_min_kb",
               "low_memory", "trim_level_max", "cpu_ms", "thermal_max"]
TRIM_MEMORY_RUNNING_LOW = 10
THERMAL_STATUS_MODERATE = 2


def median(values: list[float]) -> float | None:
    return statistics.median(values) if values else None


def fmt(value: float | None, digits: int = 0) -> str:
    return "—" if value is None else f"{value:.{digits}f}"


def per(rows: list[dict], numerator: str, denominator) -> list[float]:
    values = []
    for r in rows:
        top, bottom = r.get(numerator), denominator(r)
        if top is not None and bottom:
            values.append(top / bottom)
    return values


def died_takes(lines: list[dict]) -> list[tuple[dict, dict]]:
    died, open_start = [], None
    for line in lines:
        event = line.get("event")
        if event == "start":
            open_start = line
        elif event in ("insert", "discard") and open_start and line.get("id") == open_start["id"]:
            open_start = None
        elif event == "exit" and open_start:
            died.append((open_start, line))
            open_start = None
    return died


def edited_share(lines: list[dict]) -> tuple[int, int]:
    """How many inserts have a later `edit` line whose text_after no longer contains the inserted text."""
    after = {line["id"]: line["text_after"] for line in lines if line.get("event") == "edit"}
    inserts = [line for line in lines if line.get("event") == "insert" and line["id"] in after]
    edited = sum(line["cleaned"].strip() not in after[line["id"]] for line in inserts)
    return edited, len(inserts)


def summarize(lines: list[dict], capacity_mah: float = 5000) -> str:
    inserts = [line for line in lines if line.get("event") == "insert"]
    unplugged = [r for r in inserts if not r.get("plugged")]
    out = [f"{len(inserts)} inserts, {len(inserts) - len(unplugged)} excluded as plugged"]
    missing = {f: sum(r.get(f) is None for r in inserts) for f in COST_FIELDS}
    out.append("lines lacking a field: " + (", ".join(f"{f} {n}" for f, n in missing.items() if n) or "none"))

    groups = defaultdict(list)
    for r in unplugged:
        groups[(r.get("asr_backend"), r.get("clean_backend"))].append(r)
    audio_s = lambda r: (r.get("audio_ms") or 0) / 1000  # noqa: E731
    audio_min = lambda r: audio_s(r) / 60  # noqa: E731
    for (asr, clean), rows in sorted(groups.items(), key=str):
        charge_per_min = median(per(rows, "charge_uah", audio_min))
        out.append(f"\n{asr}/{clean}: {len(rows)} takes")
        out.append(f"  asr_ms per audio second: {fmt(median(per(rows, 'asr_ms', audio_s)), 1)}")
        out.append(f"  clean_ms per output token: {fmt(median(per(rows, 'clean_ms', lambda r: r.get('out_tokens'))), 1)}")
        out.append(f"  charge_uah per audio minute: {fmt(charge_per_min)}"
                   f" (battery % per 10 min: {fmt(None if charge_per_min is None else charge_per_min * 10 / (capacity_mah * 1000) * 100, 2)})")
        pss = [r["peak_pss_kb"] for r in rows if r.get("peak_pss_kb") is not None]
        avail = [r["avail_mem_min_kb"] for r in rows if r.get("avail_mem_min_kb") is not None]
        out.append(f"  peak_pss_kb median {fmt(median(pss))} max {fmt(max(pss) if pss else None)}; "
                   f"lowest avail_mem_kb {fmt(min(avail) if avail else None)}")
        out.append(f"  cpu_ms per audio second: {fmt(median(per(rows, 'cpu_ms', audio_s)))}")
        out.append(f"  low_memory {sum(bool(r.get('low_memory')) for r in rows)}, "
                   f"trim >= RUNNING_LOW {sum((r.get('trim_level_max') or 0) >= TRIM_MEMORY_RUNNING_LOW for r in rows)}, "
                   f"thermal >= MODERATE {sum((r.get('thermal_max') or 0) >= THERMAL_STATUS_MODERATE for r in rows)}")
        for mode in sorted({r.get("clean_mode") for r in rows}, key=str):
            stops = [r["stop_to_insert_ms"] for r in rows if r.get("clean_mode") == mode and r.get("stop_to_insert_ms") is not None]
            out.append(f"  stop_to_insert_ms [{mode}] median {fmt(median(stops))} over {len(stops)}")

    cues = Counter(r["cue"] for r in inserts if r.get("cue"))
    out.append("\ncue matches: " + (", ".join(f"{c} {n}" for c, n in cues.most_common()) or "none"))
    edited, total = edited_share(lines)
    out.append(f"inserts whose text was edited afterwards: {edited} of {total} with an edit line")

    exits = Counter(line.get("reason") for line in lines if line.get("event") == "exit")
    out.append("process exits: " + (", ".join(f"{k} {n}" for k, n in exits.most_common()) or "none"))
    for start, exit_line in died_takes(lines):
        out.append(f"  take {start['id']} died: {exit_line.get('reason')}, pss_kb {exit_line.get('pss_kb')}, "
                   f"{exit_line.get('description')}")
    return "\n".join(out)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("edits", type=Path)
    parser.add_argument("--capacity-mah", type=float, default=5000)
    args = parser.parse_args()
    print(summarize(paths.read_jsonl(args.edits), args.capacity_mah))
    return 0


if __name__ == "__main__":
    sys.exit(main())
