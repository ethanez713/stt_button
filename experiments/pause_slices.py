"""What cutting slices at pauses costs in accuracy, and how much it leaves for after the stop.

    STT_MODEL_DIR=~/models/stt_button pyrun python -m experiments.pause_slices [--pause 0.6 0.8 1.2]

Each recording is sliced with the design's 30 s rule alone and with pause cuts at each pause_s,
and every slice is recognized by the host recognizer. WER is against the reference on read
passages. Changed words are the word errors between a candidate's text and the 30 s text, per 100
words of the 30 s text, over all recordings.
"""

import argparse
import statistics
import sys

from experiments import paths, wer
from stt_button import config
from stt_button.asr import SAMPLE_RATE, Recognizer, join_slices, read_wav, slice_bounds


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--pause", type=float, nargs="+", default=[0.6, 0.8, 1.2])
    args = parser.parse_args()

    cfg = config.load()
    recognizer = Recognizer()
    cache: dict[tuple[str, int, int], str] = {}
    arms = [None, *args.pause]
    stats = {arm: {"errors": 0, "ref": 0, "changed": 0, "words": 0, "cuts": 0, "minutes": 0.0, "tails": []}
             for arm in arms}

    for meta in paths.sidecars():
        samples = read_wav(paths.RECORDINGS / f"{meta['id']}.wav")
        texts = {}
        for arm in arms:
            pause = {} if arm is None else {"pause_s": arm, "slice_min_s": 2, "pause_margin_db": 10}
            bounds = slice_bounds(samples, cfg["slice_max_s"], cfg["slice_search_s"], **pause)
            for a, b in bounds:
                if (meta["id"], a, b) not in cache:
                    cache[(meta["id"], a, b)] = recognizer.recognize(samples[a:b])
            texts[arm] = join_slices([cache[(meta["id"], a, b)] for a, b in bounds])
            s = stats[arm]
            s["cuts"] += len(bounds) - 1
            s["minutes"] += len(samples) / SAMPLE_RATE / 60
            s["tails"].append((bounds[-1][1] - bounds[-1][0]) / SAMPLE_RATE)
            if meta["mode"] == "read":
                reference = wer.normalize(meta["reference"])
                s["errors"] += wer.word_errors(reference, wer.normalize(texts[arm]))
                s["ref"] += len(reference)
        base = wer.normalize(texts[None])
        for arm in args.pause:
            stats[arm]["changed"] += wer.word_errors(base, wer.normalize(texts[arm]))
            stats[arm]["words"] += len(base)

    print("| Slicing | Read WER | Changed words per 100 | Cuts per minute | Median last slice (s) |")
    print("|---|---|---|---|---|")
    for arm in arms:
        s = stats[arm]
        label = "30 s only" if arm is None else f"pause {arm} s"
        changed = "—" if arm is None else f"{100 * s['changed'] / s['words']:.1f}"
        print(f"| {label} | {100 * s['errors'] / s['ref']:.2f}% | {changed} | {s['cuts'] / s['minutes']:.1f} | "
              f"{statistics.median(s['tails']):.1f} |")
    return 0


if __name__ == "__main__":
    sys.exit(main())
