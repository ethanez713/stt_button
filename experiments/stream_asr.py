"""A streaming recognizer's accuracy, cost and churn, fed recordings in 0.1 s increments.

    STT_MODEL_DIR=~/models/stt_button pyrun python -m experiments.stream_asr [--model fc-stream480]
        [--threads 2] [--modes read]

The model is a sherpa-onnx NeMo streaming transducer directory under STT_MODEL_DIR. WER is scored on
read passages only. Churn counts replaced or deleted words between consecutive results, ignoring case
and punctuation.
"""

import argparse
import sys
import time

import numpy as np

from experiments import paths, wer
from experiments.commit_windows import changed_words
from stt_button import config
from stt_button.asr import SAMPLE_RATE, read_wav

FEED_S = 0.1
TAIL_SILENCE_S = 1.0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--model", default="fc-stream480")
    parser.add_argument("--threads", type=int, default=2)
    parser.add_argument("--modes", nargs="+", default=["read"])
    args = parser.parse_args()

    import sherpa_onnx

    model = config.model_dir() / args.model
    recognizer = sherpa_onnx.OnlineRecognizer.from_transducer(
        tokens=str(model / "tokens.txt"), encoder=str(model / "encoder.int8.onnx"),
        decoder=str(model / "decoder.int8.onnx"), joiner=str(model / "joiner.int8.onnx"),
        num_threads=args.threads, decoding_method="greedy_search", model_type="nemo_transducer")
    takes = [m for m in paths.sidecars() if m["mode"] in args.modes]
    if not takes:
        sys.exit(f"no {'/'.join(args.modes)} recordings in {paths.RECORDINGS}")

    def decode_ready(stream) -> None:
        while recognizer.is_ready(stream):
            recognizer.decode_stream(stream)

    step = int(FEED_S * SAMPLE_RATE)
    errors = ref_words = changed = final_words = 0
    decode_s = audio_s = 0.0
    for meta in takes:
        samples = read_wav(paths.RECORDINGS / f"{meta['id']}.wav")
        stream = recognizer.create_stream()
        shown = []
        started = time.monotonic()
        for i in range(0, len(samples), step):
            stream.accept_waveform(SAMPLE_RATE, samples[i:i + step])
            decode_ready(stream)
            shown.append(recognizer.get_result(stream))
        # Trailing silence flushes the last chunk out of the model's lookahead.
        stream.accept_waveform(SAMPLE_RATE, np.zeros(int(TAIL_SILENCE_S * SAMPLE_RATE), dtype=np.float32))
        stream.input_finished()
        decode_ready(stream)
        final = recognizer.get_result(stream).strip()
        shown.append(final)
        decode_s += time.monotonic() - started
        audio_s += len(samples) / SAMPLE_RATE
        changed += changed_words(shown, normalized=True)
        final_words += len(final.split())
        if meta["mode"] == "read":
            reference = wer.normalize(meta["reference"])
            errors += wer.word_errors(reference, wer.normalize(final))
            ref_words += len(reference)
    wer_text = f"WER {100 * errors / ref_words:.2f}%  " if ref_words else ""
    print(f"{args.model} threads={args.threads}: {wer_text}decode/audio {decode_s / audio_s:.3f}  "
          f"changed words/100 {100 * changed / max(1, final_words):.0f}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
