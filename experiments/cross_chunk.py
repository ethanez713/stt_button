"""Does cleaning a correction together with the chunk before it resolve it?

    STT_MODEL_DIR=~/models/stt_button pyrun python -m experiments.cross_chunk [--fillers 3]

Each short self-correction recording s25-s30 is split at its cue and placed so that a chunk ends
before the correction: filler sentences from a ramble, the statement, then the correction and more
filler. chunk_min_tokens is set to close the first chunk exactly after the statement. The baseline
cleans the chunks as live_chunks cuts them; the joined arm passes the correction cues, so the
correction's chunk is cleaned together with the one before it.
"""

import argparse
import re
import sys

from experiments import paths
from stt_button import config
from stt_button.cleaner import Cleaner, live_chunks, split_sentences

SPLIT_CASES = {
    "s25": ("Okay, how about let's meet Tuesday?", "Actually make that Wednesday.", ["wednesday"], ["tuesday"]),
    "s26": ("Dinner is at seven.", "I mean eight, not seven.", ["eight", "8"], ["at seven", "at 7"]),
    "s27": ("When you're at the store, can you grab some apples?", "Not apples, pears?", ["pears"], ["apples"]),
    "s28": ("I'll be there in ten minutes.", "No, twenty minutes.", ["twenty", "20"], ["ten minutes", "10 minutes"]),
    "s29": ("Okay, whenever you're ready, the meeting's in room four.", "No, five. Room five.", ["five", "5"], ["four", "4"]),
    "s30": ("My number is 555-010.", "No, 555-010-7896.", ["010-7896"], ["757."]),
}


def resolved(text: str, wanted: list[str], unwanted: list[str]) -> bool:
    def has(word: str) -> bool:
        if not word.isalnum():
            return word in text.lower()
        return re.search(rf"(?<![\w-]){re.escape(word)}(?![\w-])", text.lower()) is not None

    return any(has(w) for w in wanted) and not any(has(u) for u in unwanted)


def ramble_sentences(index: int) -> list[str]:
    files = sorted((paths.EXPERIMENTS / "asr" / "unified").glob("m*.txt"))
    return split_sentences(files[index % len(files)].read_text())


def build_case(cleaner: Cleaner, statement: str, correction: str, rambles: list[str]) -> tuple[str, int]:
    """The text, and the chunk size that closes the first chunk right after the statement."""
    filler: list[str] = []
    for sentence in rambles:
        filler.append(sentence)
        if cleaner.count_tokens(" ".join(filler)) >= 100:
            break
    head = " ".join(filler + [statement])
    return " ".join([head, correction] + rambles[len(filler):len(filler) + 4]), cleaner.count_tokens(head)


def clean_all(cleaner: Cleaner, chunks: list[str]) -> str:
    return "\n\n".join(cleaner.clean_chunk(c)[0] for c in chunks)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--fillers", type=int, default=3)
    args = parser.parse_args(argv)
    cfg = config.load()
    cleaner = Cleaner(backend="ollama")
    totals = {"baseline": 0, "joined": 0}
    runs = 0
    for name, (statement, correction, wanted, unwanted) in SPLIT_CASES.items():
        for i in range(args.fillers):
            text, closes_at = build_case(cleaner, statement, correction, ramble_sentences(i))
            chunks = live_chunks(text, cleaner.count_tokens, closes_at, cfg["chunk_max_tokens"], final=True)
            merged = live_chunks(text, cleaner.count_tokens, closes_at, cfg["chunk_max_tokens"], final=True,
                                 cues=cfg["self_correction_cues"])
            base_ok = resolved(clean_all(cleaner, chunks), wanted, unwanted)
            joined_ok = resolved(clean_all(cleaner, merged), wanted, unwanted)
            totals["baseline"] += base_ok
            totals["joined"] += joined_ok
            runs += 1
            print(f"{name} filler{i}: chunks {len(chunks)} -> {len(merged)}  baseline {'ok' if base_ok else 'MISS'}  joined {'ok' if joined_ok else 'MISS'}")
    print(f"resolved of {runs}: baseline {totals['baseline']}, joined {totals['joined']}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
