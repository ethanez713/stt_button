"""Punctuation and capitals that a slice boundary invents, and what a join repair does about them.

    STT_MODEL_DIR=~/models/stt_button pyrun python -m experiments.boundary_punct [--pause 0.8 1.2 1.6]

Each recording is recognized with the 30 s rule alone (the reference) and with pause cuts at each
pause_s. The words of a candidate are aligned to the reference's, and at every slice boundary:

- a false break is a left slice whose last word ends in . ! or ? where the reference word does not;
- a false capital is a right slice whose first word is capitalized (not I) where the reference's is not.

Join repair J lowercases the first letter of a slice's first word when the slice before it does not
end in . ! or ?, unless the word is I or an I contraction. A J-harm is a word J lowercases that the
reference capitalizes. Errors are counted per 100 words of the reference text.
"""

import argparse
import difflib
import re
import statistics
import sys

from experiments import paths, wer
from stt_button import config
from stt_button.asr import SAMPLE_RATE, Recognizer, join_slices, read_wav, slice_bounds

SENTENCE_END = re.compile(r"[.!?][\"')\]]*$")


def key(word: str) -> str:
    return re.sub(r"[^a-z0-9']", "", word.lower()).strip("'")


def is_capitalized(word: str) -> bool:
    letters = re.sub(r"^[^A-Za-z]+", "", word)
    return bool(letters) and letters[0].isupper() and not re.fullmatch(r"I(?:'[a-z]+)?\W*", letters)


def is_lowercase(word: str) -> bool:
    letters = re.sub(r"^[^A-Za-z]+", "", word)
    return bool(letters) and letters[0].islower()


def aligned(candidate: list[str], reference: list[str]) -> dict[int, int]:
    matcher = difflib.SequenceMatcher(a=[key(w) for w in candidate], b=[key(w) for w in reference], autojunk=False)
    return {a + k: b + k for a, b, size in matcher.get_matching_blocks() for k in range(size)}


def boundary_counts(slice_texts: list[str], reference_text: str) -> dict[str, int]:
    texts = [t for t in slice_texts if t]
    candidate = " ".join(texts).split()
    reference = reference_text.split()
    match = aligned(candidate, reference)
    counts = dict.fromkeys(("breaks", "caps", "caps_after_j", "j_fixed", "j_harms"), 0)
    first = 0
    for text in texts[:-1]:
        first += len(text.split())
        left, right = first - 1, first
        if left in match and SENTENCE_END.search(candidate[left]) and not SENTENCE_END.search(reference[match[left]]):
            counts["breaks"] += 1
        if right not in match or not is_capitalized(candidate[right]):
            continue
        ref_word = reference[match[right]]
        wrong = is_lowercase(ref_word)
        counts["caps"] += wrong
        if SENTENCE_END.search(candidate[left]):
            counts["caps_after_j"] += wrong
        elif wrong:
            counts["j_fixed"] += 1
        else:
            counts["j_harms"] += is_capitalized(ref_word)
    return counts


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--pause", type=float, nargs="+", default=[0.8, 1.2, 1.6])
    args = parser.parse_args()

    cfg = config.load()
    recognizer = Recognizer()
    cache: dict[tuple[str, int, int], str] = {}
    stats = {arm: {"counts": dict.fromkeys(("breaks", "caps", "caps_after_j", "j_fixed", "j_harms"), 0),
                   "words": 0, "cuts": 0, "minutes": 0.0, "tails": [], "errors": 0, "ref": 0}
             for arm in args.pause}

    def text_for(meta_id: str, samples, bounds) -> list[str]:
        for a, b in bounds:
            if (meta_id, a, b) not in cache:
                cache[(meta_id, a, b)] = recognizer.recognize(samples[a:b])
        return [cache[(meta_id, a, b)] for a, b in bounds]

    for meta in paths.sidecars():
        samples = read_wav(paths.RECORDINGS / f"{meta['id']}.wav")
        reference = join_slices(text_for(meta["id"], samples, slice_bounds(samples, cfg["slice_max_s"], cfg["slice_search_s"])))
        for arm in args.pause:
            bounds = slice_bounds(samples, cfg["slice_max_s"], cfg["slice_search_s"],
                                  pause_s=arm, slice_min_s=2, pause_margin_db=10)
            slice_texts = text_for(meta["id"], samples, bounds)
            s = stats[arm]
            for name, value in boundary_counts(slice_texts, reference).items():
                s["counts"][name] += value
            s["words"] += len(reference.split())
            s["cuts"] += len(bounds) - 1
            s["minutes"] += len(samples) / SAMPLE_RATE / 60
            s["tails"].append((bounds[-1][1] - bounds[-1][0]) / SAMPLE_RATE)
            if meta["mode"] == "read":
                ref = wer.normalize(meta["reference"])
                s["errors"] += wer.word_errors(ref, wer.normalize(join_slices(slice_texts)))
                s["ref"] += len(ref)

    print("| pause_s | Read WER | False breaks /100 | False capitals /100 | Capitals after J /100 | J fixes | J harms "
          "| Cuts/min | Median last slice (s) |")
    print("|---|---|---|---|---|---|---|---|---|")
    for arm in args.pause:
        s, c = stats[arm], stats[arm]["counts"]
        per100 = lambda n: f"{100 * n / s['words']:.2f}"  # noqa: E731
        print(f"| {arm} | {100 * s['errors'] / s['ref']:.2f}% | {per100(c['breaks'])} | {per100(c['caps'])} | "
              f"{per100(c['caps_after_j'])} | {c['j_fixed']} | {c['j_harms']} | {s['cuts'] / s['minutes']:.1f} | "
              f"{statistics.median(s['tails']):.1f} |")
    return 0


if __name__ == "__main__":
    sys.exit(main())
