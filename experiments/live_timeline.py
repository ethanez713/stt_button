"""Estimate the phone's stop-to-inserted-text time per recording, as written and with live cleaning.

    STT_MODEL_DIR=~/models/stt_button pyrun python -m experiments.live_timeline [--k 64 128 256]

Each recording is cut into the design's 30 s slices and recognized on the host for its text; the
times are the phone's, from the speeds measured in docs/plans/results/09 and 10:
  as written  last slice recognized after stop, cleaner loaded, whole transcript cleaned.
  live        cleaner resident; whole-sentence chunks of >= K committed tokens are cleaned during
              the take; after stop the last slice is recognized and the uncleaned rest is cleaned.
              Takes the skip rule sends raw get no clean at all.
"""

import argparse
import statistics
import sys

import numpy as np

from experiments import paths
from stt_button import config
from stt_button.cleaner import skips_cleaner, split_sentences

SAMPLE_RATE = 16000
# Phone, 6 threads: decode seconds per audio window (results/10, step 7).
ASR_WINDOWS = ([0, 2, 5, 10, 30], [0, 0.12, 0.22, 0.60, 2.22])
CLEANER_LOAD_S = 1.06
PROMPT_TOKENS = 78
PREFILL_TOK_S = 320
DECODE_TOK_S = 127  # prompt lookup, best-of-3 median


def asr_s(audio_s: float) -> float:
    return float(np.interp(audio_s, *ASR_WINDOWS))


def clean_s(tokens: int) -> float:
    """Output length tracks input length for this cleaner."""
    return (PROMPT_TOKENS + tokens) / PREFILL_TOK_S + tokens / DECODE_TOK_S


def as_written(slices: list[tuple[float, float, str]], count) -> float:
    start, end, _ = slices[-1]
    whole = " ".join(t for _, _, t in slices)
    return asr_s(end - start) + CLEANER_LOAD_S + clean_s(count(whole))


def live(slices: list[tuple[float, float, str]], count, k: int) -> float:
    """One worker runs slices and chunk cleans in order: run concurrently on the phone, the cleaner
    decoded 3-14x slower (results/10, contention)."""
    whole = " ".join(t for _, _, t in slices)
    worker_free = 0.0
    pending: list[str] = []
    committed: list[str] = []
    for start, end, text in slices[:-1]:
        worker_free = max(worker_free, end) + asr_s(end - start)
        committed += split_sentences(text)
        # The newest sentence may continue into the next slice, so it is held back.
        ready, committed = committed[:-1], committed[-1:]
        for sentence in ready:
            pending.append(sentence)
            if count(" ".join(pending)) >= k:
                worker_free += clean_s(count(" ".join(pending)))
                pending = []
    start, stop, last_text = slices[-1]
    done = max(worker_free, stop) + asr_s(stop - start)
    rest = " ".join(pending + committed + [last_text]).strip()
    if rest and not skips_cleaner(whole, config.load()):
        done += clean_s(count(rest))
    return done - stop


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--k", type=int, nargs="+", default=[64, 128, 256])
    args = parser.parse_args()

    from llama_cpp import Llama

    from stt_button.asr import Recognizer, read_wav, slice_bounds

    cfg = config.load()
    vocab = Llama(model_path=str(config.model_dir() / cfg["cleaners"]["s1-mini"]["model"]), vocab_only=True,
                  verbose=False)
    count = lambda text: len(vocab.tokenize(text.encode(), add_bos=False, special=False))
    recognizer = Recognizer()
    rows = []
    for meta in paths.sidecars():
        samples = read_wav(paths.RECORDINGS / f"{meta['id']}.wav")
        slices = [(a / SAMPLE_RATE, b / SAMPLE_RATE, recognizer.recognize(samples[a:b]))
                  for a, b in slice_bounds(samples, cfg["slice_max_s"], cfg["slice_search_s"])]
        whole = " ".join(t for _, _, t in slices)
        rows.append({"id": meta["id"], "short": skips_cleaner(whole, cfg), "audio_s": slices[-1][1],
                     "written": as_written(slices, count), **{k: live(slices, count, k) for k in args.k}})

    for label, group in (("all", rows), ("skipped short", [r for r in rows if r["short"]]),
                         ("cleaned", [r for r in rows if not r["short"]])):
        if not group:
            continue
        print(f"{label} (n={len(group)}, median audio {statistics.median(r['audio_s'] for r in group):.0f} s):")
        for key in ["written", *args.k]:
            values = sorted(r[key] for r in group)
            name = "as written" if key == "written" else f"live K={key}"
            print(f"  {name:11} median {statistics.median(values):.1f} s, p90 {values[int(0.9 * len(values))]:.1f} s,"
                  f" max {values[-1]:.1f} s")
    return 0


if __name__ == "__main__":
    sys.exit(main())
