"""Preview commit window against accuracy, cost and churn, on the read passages at U = 1 s.

    STT_MODEL_DIR=~/models/stt_button pyrun python -m experiments.commit_windows

Replays each read passage through experiments.live_asr.simulate with shorter slice_max_s, scoring the
committed text against the reference. Churn counts replaced or deleted words between consecutive
displays, not prefix retractions.
"""

import difflib
import sys
import time

from experiments import paths, wer
from experiments.live_asr import simulate
from stt_button.asr import SAMPLE_RATE, Recognizer, read_wav

WINDOWS = ((10, 5), (6, 3), (4, 2), (3, 1.5))


def changed_words(displayed: list[str], normalized: bool) -> int:
    words = wer.normalize if normalized else str.split
    changed = 0
    for before, after in zip(displayed, displayed[1:]):
        matcher = difflib.SequenceMatcher(a=words(before), b=words(after), autojunk=False)
        changed += sum(i2 - i1 for op, i1, i2, _, _ in matcher.get_opcodes() if op in ("replace", "delete"))
    return changed


def main() -> int:
    recognizer = Recognizer()
    reads = [m for m in paths.sidecars() if m["mode"] == "read"]
    if not reads:
        sys.exit(f"no read passages in {paths.RECORDINGS}")
    audio = {m["id"]: read_wav(paths.RECORDINGS / f"{m['id']}.wav") for m in reads}
    references = {m["id"]: wer.normalize(m["reference"]) for m in reads}
    ref_words = sum(len(r) for r in references.values())

    errors = sum(wer.word_errors(references[i], wer.normalize(recognizer.transcribe(paths.RECORDINGS / f"{i}.wav").text))
                 for i in audio)
    print(f"30 s slicing: WER {100 * errors / ref_words:.2f}%", flush=True)
    for max_s, search_s in WINDOWS:
        errors = raw_changed = norm_changed = final_words = 0
        decode_s = audio_s = 0.0
        for i, samples in audio.items():
            def timed(chunk):
                nonlocal decode_s
                started = time.monotonic()
                try:
                    return recognizer.recognize(chunk)
                finally:
                    decode_s += time.monotonic() - started

            replay = simulate(samples, 1.0, search_s, timed, slice_max_s=max_s)
            errors += wer.word_errors(references[i], wer.normalize(replay.committed_text))
            audio_s += len(samples) / SAMPLE_RATE
            raw_changed += changed_words(replay.displayed, normalized=False)
            norm_changed += changed_words(replay.displayed, normalized=True)
            final_words += len(replay.committed_text.split())
        print(f"commit window {max_s} s (search {search_s}): WER {100 * errors / ref_words:.2f}%  "
              f"decode/audio {decode_s / audio_s:.3f}  changed words/100: {100 * raw_changed / final_words:.0f} raw, "
              f"{100 * norm_changed / final_words:.0f} ignoring case and punctuation", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
