"""How much S1-mini changes the recognizer's text, and what it costs, by take length.

    STT_MODEL_DIR=~/models/stt_button pyrun python -m experiments.short_threshold

Recognizes any recording without a transcript and cleans any transcript without a whole clean,
then classifies each raw/cleaned pair as identical, punctuation-and-case only, or word changes.
"""

import re
import statistics
import sys
import time

from experiments import paths
from experiments.backend_parity import word_changes
from stt_button import config

BANDS = [(1, 5), (6, 10), (11, 20), (21, 40), (41, 10**9)]


def bare_words(text: str) -> list[str]:
    return re.sub(r"[^\w\s']", " ", text.lower()).split()


def classify(raw: str, cleaned: str) -> str:
    if raw == cleaned:
        return "identical"
    if bare_words(raw) == bare_words(cleaned):
        return "punct"
    return "words"


def main() -> int:
    recognizer_name = config.load()["recognizer"]
    asr_dir = paths.ASR / recognizer_name
    clean_dir = paths.CLEAN / "s1-mini"
    ids = [m["id"] for m in paths.sidecars()]
    missing_raw = [i for i in ids if not (asr_dir / f"{i}.txt").exists()]
    if missing_raw:
        from stt_button.asr import Recognizer

        recognizer = Recognizer(recognizer_name)
        for i in missing_raw:
            (asr_dir / f"{i}.txt").write_text(recognizer.transcribe(paths.RECORDINGS / f"{i}.wav").text + "\n")
        del recognizer
    from stt_button.cleaner import Cleaner

    cleaner = Cleaner()
    cleaner.clean("Warm up.", skip=False)
    rows = []
    for i in ids:
        raw = (asr_dir / f"{i}.txt").read_text().strip()
        started = time.monotonic()
        cleaned = cleaner.clean(raw, skip=False, min_tokens=config.load()["chunk_max_tokens"]).text
        ms = int((time.monotonic() - started) * 1000)
        if not (clean_dir / f"{i}.txt").exists():
            (clean_dir / f"{i}.txt").write_text(cleaned + "\n")
        rows.append({"id": i, "words": len(raw.split()), "class": classify(raw, cleaned), "ms": ms,
                     "raw": raw, "cleaned": cleaned})

    for lo, hi in BANDS:
        band = [r for r in rows if lo <= r["words"] <= hi]
        if not band:
            continue
        counts = {c: sum(r["class"] == c for r in band) for c in ("identical", "punct", "words")}
        print(f"{lo}-{hi if hi < 10**9 else '+'} words: n={len(band)} {counts} "
              f"median host clean {statistics.median(r['ms'] for r in band)} ms")
    print()
    for r in sorted(rows, key=lambda r: r["words"]):
        if r["words"] <= 40 and r["class"] != "identical":
            print(f"{r['id'][:3]} ({r['words']}w, {r['class']}): {word_changes(r['raw'], r['cleaned'])[:200]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
