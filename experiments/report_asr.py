"""Summarize data/experiments/asr/results.jsonl and apply the phase 03 decision rule.

    pyrun python -m experiments.report_asr
"""

import difflib
import statistics
import sys

from experiments import paths

ENGLISH_ONLY = {"unified", "tdt-v2"}
CLOSE_POINTS = 0.3
FASTER_BY = 1.5


def summarize(results: list[dict]) -> dict[str, dict]:
    summary = {}
    for arm in sorted({r["arm"] for r in results}):
        rows = [r for r in results if r["arm"] == arm]
        reads = [r for r in rows if r["mode"] == "read"]
        ref = sum(r["ref_words"] for r in reads)
        ref_nonum = sum(r["ref_words_nonum"] for r in reads)
        rtfs = [r["rtf"] for r in rows if r["rtf"] is not None]
        summary[arm] = {
            "reads": len(reads),
            "wer": 100 * sum(r["errors"] for r in reads) / ref if ref else None,
            "wer_nonum": 100 * sum(r["errors_nonum"] for r in reads) / ref_nonum if ref_nonum else None,
            "median_rtf": statistics.median(rtfs) if rtfs else None,
            "worst": sorted(reads, key=lambda r: r["errors_nonum"] / max(1, r["ref_words_nonum"]), reverse=True)[:3],
        }
    return summary


def verdict(summary: dict[str, dict]) -> str:
    """Lowest number-free WER wins, unless a close arm is much faster; English-only breaks near-ties."""
    scored = {a: s for a, s in summary.items() if s["wer_nonum"] is not None and s["median_rtf"]}
    if not scored:
        return "no scored arms"
    best = min(scored, key=lambda a: scored[a]["wer_nonum"])
    close = [a for a in scored if a != best and scored[a]["wer_nonum"] - scored[best]["wer_nonum"] <= CLOSE_POINTS]
    faster = [a for a in close if scored[best]["median_rtf"] >= FASTER_BY * scored[a]["median_rtf"]]
    if faster:
        pick = min(faster, key=lambda a: scored[a]["median_rtf"])
        return f"{pick} (within {CLOSE_POINTS} points of {best} and at least {FASTER_BY}x faster)"
    if best not in ENGLISH_ONLY:
        english = [a for a in close if a in ENGLISH_ONLY]
        if english:
            pick = min(english, key=lambda a: scored[a]["wer_nonum"])
            return f"{pick} (English-only, within {CLOSE_POINTS} points of {best} at similar speed)"
    return f"{best} (lowest number-free WER)"


def word_diff(a: str, b: str, context: int = 3) -> list[str]:
    wa, wb = a.split(), b.split()
    lines = []
    for op, i1, i2, j1, j2 in difflib.SequenceMatcher(a=wa, b=wb, autojunk=False).get_opcodes():
        if op == "equal":
            continue
        before = " ".join(wa[max(0, i1 - context):i1])
        lines.append(f"    ...{before} [{' '.join(wa[i1:i2])} -> {' '.join(wb[j1:j2])}]")
    return lines


def main() -> int:
    results = paths.read_jsonl(paths.ASR_RESULTS)
    if not results:
        sys.exit(f"no results in {paths.ASR_RESULTS}; run experiments.asr_bakeoff first")
    summary = summarize(results)

    print(f"{'arm':10} {'reads':>5} {'WER':>6} {'WER no-num':>10} {'median RTF':>11}")
    for arm, s in summary.items():
        wer_text = f"{s['wer']:.2f}" if s["wer"] is not None else "-"
        nonum_text = f"{s['wer_nonum']:.2f}" if s["wer_nonum"] is not None else "-"
        rtf_text = f"{s['median_rtf']:.4f}" if s["median_rtf"] is not None else "-"
        print(f"{arm:10} {s['reads']:>5} {wer_text:>6} {nonum_text:>10} {rtf_text:>11}")
    for arm, s in summary.items():
        worst = ", ".join(f"{r['id']} ({r['errors_nonum']}/{r['ref_words_nonum']})" for r in s["worst"])
        print(f"  worst {arm}: {worst}")

    ids_by_arm = {arm: {r["id"] for r in results if r["arm"] == arm} for arm in summary}
    missing = {arm: len(set().union(*ids_by_arm.values()) - ids) for arm, ids in ids_by_arm.items()}
    print(f"recordings missing per arm (should be 0): {missing}")

    ranked = sorted((a for a in summary if summary[a]["wer_nonum"] is not None), key=lambda a: summary[a]["wer_nonum"])
    if len(ranked) >= 2:
        a, b = ranked[:2]
        rambles = sorted({r["id"] for r in results if r["mode"] == "ramble"})
        texts = {arm: {i: (paths.ASR / arm / f"{i}.txt").read_text().strip() for i in rambles
                       if (paths.ASR / arm / f"{i}.txt").exists()} for arm in (a, b)}
        common = [i for i in rambles if i in texts[a] and i in texts[b]]
        distance = {i: 1 - difflib.SequenceMatcher(a=texts[a][i].split(), b=texts[b][i].split()).ratio() for i in common}
        differing = [i for i in common if distance[i] > 0]
        print(f"\nrambles whose {a} and {b} transcripts differ: {len(differing)} of {len(common)}")
        for i in sorted(differing, key=distance.get, reverse=True)[:5]:
            print(f"  {i}  [{a} -> {b}]")
            print("\n".join(word_diff(texts[a][i], texts[b][i])))

    print(f"\nverdict: {verdict(summary)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
