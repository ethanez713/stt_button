"""Punctuation and capitals that a slice boundary invents, and what a join repair does about them.

    STT_MODEL_DIR=~/models/stt_button pyrun python -m experiments.boundary_punct [--pause 0.8 1.2 1.6]
    STT_MODEL_DIR=~/models/stt_button pyrun python -m experiments.boundary_punct --grid [--cut-pause 0.5 0.6 0.8 1.0 1.2] [--hard-cut 8 10 15]

Each recording is recognized with the 30 s rule alone (the reference) and with pause cuts at each
pause_s. The words of a candidate are aligned to the reference's, and at every slice boundary:

- a false break is a left slice whose last word ends in . ! or ? where the reference word does not;
- a false capital is a right slice whose first word is capitalized (not I) where the reference's is not.

With --thinking it measures what a 2.5 s thinking pause does: pauses are spliced into each recording at
word gaps of the 30 s decode, and the plain slice decodes (baseline) are compared with context decodes
(each slice decoded with the speech before the pause, then split at the junction) at those gaps.

With --grid each recording is cut at every cut pause and hard cut, with slice_min_s 2 and pause_margin_db
10. The search window stays slice_search_s and is never longer than the hard cut minus 1 s. Slices are
decoded as the phone decodes them: 4 s of context, the junction squeezed to 0.2 s. Against the same 30 s
plain-decode reference, the table gives read-passage WER, false breaks and false capitals per 100 words,
cuts per minute and the median slice length. The pick is the smallest cut pause, then the smallest hard
cut, whose read WER is at most 11.54% and whose false breaks are at most 0.5 per 100 words; otherwise it
reports that no pair passes.

Join repair J lowercases the first letter of a slice's first word when the slice before it does not
end in . ! or ?, unless the word is I or an I contraction. A J-harm is a word J lowercases that the
reference capitalizes. Errors are counted per 100 words of the reference text.
"""

import argparse
import difflib
import random
import re
import statistics
import sys

import numpy as np

from experiments import paths, wer
from stt_button import config
from stt_button.asr import (CUT_WINDOW_S, SAMPLE_RATE, Recognizer, Slicer, context_audio, join_slices, read_wav,
                            slice_bounds, split_at_junction)

CUT_PAUSES = (0.5, 0.6, 0.8, 1.0, 1.2)
HARD_CUTS = (8, 10, 15)

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


PAUSE_S = 2.5
MIN_APART_S = 3.0
PER_KIND = 3
CONTEXT_ARMS = [(c, q) for c in (1, 2, 4) for q in (0.2, None)]


def word_spans(tokens: list[str], timestamps: list[float], offset_s: float = 0.0) -> list[list]:
    """[word, first token time, last token time] per word, with timestamps shifted by offset_s."""
    words: list[list] = []
    for token, t in zip(tokens, timestamps):
        if token.startswith(" ") or not words:
            words.append([token.strip(), t + offset_s, t + offset_s])
        else:
            words[-1][0] += token
            words[-1][2] = t + offset_s
    return [w for w in words if w[0]]


def reference_words(recognizer: Recognizer, samples: np.ndarray, cfg: dict) -> list[list]:
    words: list[list] = []
    for a, b in slice_bounds(samples, cfg["slice_max_s"], cfg["slice_search_s"]):
        result = recognizer.decode(samples[a:b])
        words += word_spans(result.tokens, result.timestamps, a / SAMPLE_RATE)
    return words


def silent_windows(samples: np.ndarray) -> tuple[list[bool], np.ndarray]:
    slicer = Slicer(30, 5, 1.6)
    slicer.feed(samples)
    win = slicer.win
    n = len(slicer._speech)
    energy = np.array([float(np.square(samples[i * win:(i + 1) * win]).sum()) for i in range(n)])
    return [not s for s in slicer._speech], energy


def thinking_pauses(samples: np.ndarray, words: list[list], seed: str) -> tuple[np.ndarray, list[tuple[int, str]]]:
    """The recording with PAUSE_S of its own quietest window spliced into up to PER_KIND mid-sentence and
    PER_KIND sentence-end word gaps (at least MIN_APART_S apart); returns it and (word index before
    the gap, kind) for each splice."""
    silent, energy = silent_windows(samples)
    win = int(0.1 * SAMPLE_RATE)
    candidates = []
    for i in range(len(words) - 1):
        lo, hi = words[i][2] + 0.15, words[i + 1][1] + 0.05
        found = [w for w in range(int(lo / 0.1) + 1, min(int(hi / 0.1), len(silent))) if silent[w]]
        if found:
            kind = "end" if SENTENCE_END.search(words[i][0]) else "mid"
            candidates.append((i, kind, found[0] * win + win // 2))
    random.Random(seed).shuffle(candidates)
    chosen: list[tuple[int, str, int]] = []
    for i, kind, at in candidates:
        if sum(k == kind for _, k, _ in chosen) < PER_KIND and all(abs(at - a) >= MIN_APART_S * SAMPLE_RATE for _, _, a in chosen):
            chosen.append((i, kind, at))
    chosen.sort(key=lambda c: c[2])
    quiet = samples[int(np.argmin(energy)) * win:][:win]
    pause = np.tile(quiet, int(PAUSE_S * SAMPLE_RATE / len(quiet)) + 1)[:int(PAUSE_S * SAMPLE_RATE)]
    pieces, last = [], 0
    for _, _, at in chosen:
        pieces += [samples[last:at], pause]
        last = at
    pieces.append(samples[last:])
    return np.concatenate(pieces), [(i, kind) for i, kind, _ in chosen]


def plain_text(recognizer, samples: np.ndarray, start: int, end: int, cache: dict | None) -> str:
    key = (start, end, None)
    if cache is not None and key in cache:
        return cache[key]
    text = recognizer.recognize(samples[start:end])
    if cache is not None:
        cache[key] = text
    return text


def context_text(recognizer, samples: np.ndarray, before: int, after: int, end: int, arm, start: int,
                 cache: dict | None) -> tuple[str, str, int]:
    """(mark, slice text, decoded samples). Speech edges follow the audio and the margin, so one
    recording's cache can key a context decode by the bounds and the arm."""
    key = (start, end, (arm[0], arm[1], before, after))
    if cache is not None and key in cache:
        return cache[key]
    audio, junction = context_audio(samples, before, after, end, arm[0], arm[1])
    result = recognizer.decode(audio)
    mark, text = split_at_junction(result.tokens, result.timestamps, junction)
    value = (mark, text, len(audio))
    if cache is not None:
        cache[key] = value
    return value


def decode_slices(recognizer, samples: np.ndarray, cfg: dict, arm, *, pause_s: float = 1.6,
                  slice_max_s: float | None = None, search_s: float | None = None,
                  slice_min_s: float | None = None, pause_margin_db: float | None = None,
                  cache: dict | None = None) -> tuple[list[str], float, list[tuple[int, int]]]:
    """Slice texts under an arm, the audio seconds decoded, and the bounds.

    Defaults match the thinking measurement: pause_s 1.6 and the config's other slicer fields.
    """
    slicer = Slicer(
        cfg["slice_max_s"] if slice_max_s is None else slice_max_s,
        cfg["slice_search_s"] if search_s is None else search_s,
        pause_s,
        cfg["slice_min_s"] if slice_min_s is None else slice_min_s,
        cfg["pause_margin_db"] if pause_margin_db is None else pause_margin_db,
    )
    bounds = slicer.feed(samples) + slicer.finish()
    texts: list[str] = []
    decoded = 0.0
    for n, (start, end) in enumerate(bounds):
        before = slicer.speech_before(start) if n else None
        after = slicer.speech_after(start) if n else None
        if arm is None or before is None or after is None or after >= end:
            texts.append(plain_text(recognizer, samples, start, end, cache))
            decoded += (end - start) / SAMPLE_RATE
            continue
        mark, text, audio_len = context_text(recognizer, samples, before, after, end, arm, start, cache)
        decoded += audio_len / SAMPLE_RATE
        if not text:
            texts.append(plain_text(recognizer, samples, start, end, cache))
            decoded += (end - start) / SAMPLE_RATE
            continue
        if texts and texts[-1]:
            texts[-1] = re.sub(r"[.,?!;:]$", "", texts[-1]) + mark
        texts.append(text)
    return texts, decoded, bounds


def decode_arm(recognizer: Recognizer, samples: np.ndarray, cfg: dict, arm, *, pause_s: float = 1.6,
               slice_max_s: float | None = None, search_s: float | None = None,
               slice_min_s: float | None = None, pause_margin_db: float | None = None,
               cache: dict | None = None) -> tuple[str, float]:
    """The joined text of a pause-cut take under an arm (None = plain slice decodes), and the audio
    seconds decoded."""
    texts, decoded, _bounds = decode_slices(
        recognizer, samples, cfg, arm, pause_s=pause_s, slice_max_s=slice_max_s, search_s=search_s,
        slice_min_s=slice_min_s, pause_margin_db=pause_margin_db, cache=cache)
    return join_slices(texts), decoded


def pause_errors(candidate_text: str, reference: list[list], splices: list[tuple[int, str]]) -> dict[str, int]:
    candidate = candidate_text.split()
    ref_words = [w[0] for w in reference]
    match = aligned(candidate, ref_words)
    inverse = {r: c for c, r in match.items()}
    counts = {"breaks": 0, "missed": 0, "caps": 0, "gaps": 0, "unaligned": 0}
    for i, kind in splices:
        if i not in inverse or i + 1 not in inverse:
            counts["unaligned"] += 1
            continue
        left, right = candidate[inverse[i]], candidate[inverse[i + 1]]
        counts["gaps"] += 1
        if kind == "mid":
            counts["breaks"] += bool(SENTENCE_END.search(left)) and not SENTENCE_END.search(ref_words[i])
            counts["caps"] += is_capitalized(right) and is_lowercase(ref_words[i + 1])
        else:
            counts["missed"] += not SENTENCE_END.search(left)
    return counts


def thinking_main() -> int:
    cfg = config.load()
    recognizer = Recognizer()
    arms = [None] + CONTEXT_ARMS
    totals = {arm: {"breaks": 0, "missed": 0, "caps": 0, "gaps": 0, "unaligned": 0, "decoded": 0.0,
                    "read_errors": 0, "read_ref": 0, "cuts": 0} for arm in arms}
    for meta in paths.sidecars():
        samples = read_wav(paths.RECORDINGS / f"{meta['id']}.wav")
        words = reference_words(recognizer, samples, cfg)
        modified, splices = thinking_pauses(samples, words, meta["id"])
        print(f"{meta['id']}: {len(splices)} pauses", file=sys.stderr, flush=True)
        for arm in arms:
            t = totals[arm]
            text, decoded = decode_arm(recognizer, modified, cfg, arm)
            for name, value in pause_errors(text, words, splices).items():
                t[name] += value
            t["decoded"] += decoded
            t["cuts"] += max(0, len(splices))
            if meta["mode"] == "read":
                plain, _ = decode_arm(recognizer, samples, cfg, arm)
                ref = wer.normalize(meta["reference"])
                t["read_errors"] += wer.word_errors(ref, wer.normalize(plain))
                t["read_ref"] += len(ref)
    print("| arm | gaps | false breaks | missed breaks | false capitals | errors | read WER | decoded audio (x baseline) |")
    print("|---|---|---|---|---|---|---|---|")
    base = totals[None]["decoded"]
    for arm in arms:
        t = totals[arm]
        errors = t["breaks"] + t["missed"] + t["caps"]
        label = "baseline" if arm is None else f"context {arm[0]} s, " + ("squeezed 0.2 s" if arm[1] else "pause kept")
        print(f"| {label} | {t['gaps']} | {t['breaks']} | {t['missed']} | {t['caps']} | {errors} | "
              f"{100 * t['read_errors'] / t['read_ref']:.2f}% | {t['decoded'] / base:.2f} |")
    return 0


def pick(rows: list[dict]) -> dict | None:
    passing = [row for row in rows if row["read_wer"] <= 11.54 and row["false_breaks"] <= 0.5]
    return min(passing, key=lambda row: (row["cut_pause"], row["hard_cut"])) if passing else None


def capped_search_s(hard_cut: float, cfg_search: float) -> float:
    limit = hard_cut - 1.0
    if limit < CUT_WINDOW_S:
        # _hard_cut's argmin needs one full window; a shorter search is empty.
        limit = CUT_WINDOW_S if hard_cut >= CUT_WINDOW_S else hard_cut
    return min(cfg_search, limit)


def grid_rows(recognizer, recordings, cfg: dict, cut_pauses, hard_cuts) -> list[dict]:
    arm = (cfg["context_s"], cfg["junction_squeeze_s"])
    stats = {
        (pause, hard): {"breaks": 0, "caps": 0, "words": 0, "cuts": 0, "minutes": 0.0, "lengths": [],
                        "errors": 0, "ref": 0}
        for pause in cut_pauses for hard in hard_cuts
    }
    for meta, samples in recordings:
        cache: dict = {}
        ref_bounds = slice_bounds(samples, cfg["slice_max_s"], cfg["slice_search_s"])
        reference = join_slices(plain_text(recognizer, samples, start, end, cache) for start, end in ref_bounds)
        words = len(reference.split())
        minutes = len(samples) / SAMPLE_RATE / 60
        read_ref = wer.normalize(meta["reference"]) if meta["mode"] == "read" else None
        for pause in cut_pauses:
            for hard in hard_cuts:
                texts, _decoded, bounds = decode_slices(
                    recognizer, samples, cfg, arm, pause_s=pause, slice_max_s=hard,
                    search_s=capped_search_s(hard, cfg["slice_search_s"]), slice_min_s=2, pause_margin_db=10,
                    cache=cache)
                slot = stats[(pause, hard)]
                counts = boundary_counts(texts, reference)
                slot["breaks"] += counts["breaks"]
                slot["caps"] += counts["caps"]
                slot["words"] += words
                slot["cuts"] += len(bounds) - 1
                slot["minutes"] += minutes
                slot["lengths"].extend((end - start) / SAMPLE_RATE for start, end in bounds)
                if read_ref is not None:
                    slot["errors"] += wer.word_errors(read_ref, wer.normalize(join_slices(texts)))
                    slot["ref"] += len(read_ref)
    rows = []
    for pause in cut_pauses:
        for hard in hard_cuts:
            slot = stats[(pause, hard)]
            rows.append({
                "cut_pause": pause,
                "hard_cut": hard,
                "read_wer": 100 * slot["errors"] / slot["ref"],
                "false_breaks": 100 * slot["breaks"] / slot["words"],
                "false_caps": 100 * slot["caps"] / slot["words"],
                "cuts_per_min": slot["cuts"] / slot["minutes"],
                "median_slice_s": statistics.median(slot["lengths"]),
            })
    return rows


def format_grid(rows: list[dict]) -> str:
    lines = [
        "| cut_pause | hard_cut | Read WER | False breaks /100 | False capitals /100 | Cuts/min | Median slice (s) |",
        "|---|---|---|---|---|---|---|",
    ]
    for row in rows:
        lines.append(f"| {row['cut_pause']} | {row['hard_cut']} | {row['read_wer']:.2f}% | {row['false_breaks']:.2f} | "
                     f"{row['false_caps']:.2f} | {row['cuts_per_min']:.1f} | {row['median_slice_s']:.1f} |")
    chosen = pick(rows)
    lines.append("no pair passes" if chosen is None
                 else f"pick: cut_pause {chosen['cut_pause']}, hard_cut {chosen['hard_cut']}")
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--pause", type=float, nargs="+", default=[0.8, 1.2, 1.6])
    parser.add_argument("--thinking", action="store_true")
    parser.add_argument("--grid", action="store_true")
    parser.add_argument("--cut-pause", type=float, nargs="+", default=CUT_PAUSES)
    parser.add_argument("--hard-cut", type=float, nargs="+", default=HARD_CUTS)
    args = parser.parse_args()
    if args.thinking:
        return thinking_main()
    if args.grid:
        cfg = config.load()
        recordings = ((meta, read_wav(paths.RECORDINGS / f"{meta['id']}.wav")) for meta in paths.sidecars())
        print(format_grid(grid_rows(Recognizer(), recordings, cfg, args.cut_pause, args.hard_cut)), end="")
        return 0

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
