"""Run every recognizer arm over every recording; resumable.

    STT_MODEL_DIR=~/models/stt_button pyrun python -m experiments.asr_bakeoff [--arms unified,tdt-v2,ultra]
"""

import argparse
import gc
import json
import sys

from experiments import paths, wer
from stt_button.asr import Recognizer

ARMS = ["unified", "tdt-v2", "ultra"]


def score(reference: str, hypothesis: str) -> dict:
    ref, hyp = wer.normalize(reference), wer.normalize(hypothesis)
    ref_nonum, hyp_nonum = wer.without_numbers(ref), wer.without_numbers(hyp)
    return {
        "ref_words": len(ref),
        "errors": wer.word_errors(ref, hyp),
        "ref_words_nonum": len(ref_nonum),
        "errors_nonum": wer.word_errors(ref_nonum, hyp_nonum),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--arms", default=",".join(ARMS))
    args = parser.parse_args()

    sidecars = paths.sidecars()
    if not sidecars:
        sys.exit(f"no recordings in {paths.RECORDINGS}")
    done = {(r["id"], r["arm"]) for r in paths.read_jsonl(paths.ASR_RESULTS)}

    for arm in args.arms.split(","):
        todo = [s for s in sidecars if (s["id"], arm) not in done]
        if not todo:
            continue
        print(f"{arm}: loading", flush=True)
        recognizer = Recognizer(arm)
        out_dir = paths.ASR / arm
        out_dir.mkdir(parents=True, exist_ok=True)
        for meta in todo:
            result = recognizer.transcribe(paths.RECORDINGS / f"{meta['id']}.wav")
            (out_dir / f"{meta['id']}.txt").write_text(result.text + "\n")
            line = {
                "id": meta["id"],
                "arm": arm,
                "mode": meta["mode"],
                "audio_s": meta["duration_s"],
                "elapsed_ms": result.elapsed_ms,
                "rtf": round(result.elapsed_ms / 1000 / meta["duration_s"], 4) if meta["duration_s"] else None,
            }
            if meta["mode"] == "read":
                line |= score(meta["reference"], result.text)
            paths.append_jsonl(paths.ASR_RESULTS, line)
            print(f"  {meta['id']}: {result.elapsed_ms} ms", flush=True)
        del recognizer
        gc.collect()
    return 0


if __name__ == "__main__":
    sys.exit(main())
