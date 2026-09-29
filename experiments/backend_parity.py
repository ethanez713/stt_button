"""Clean every transcript on both cleaner backends and compare outputs and timing.

    STT_MODEL_DIR=~/models/stt_button pyrun python -m experiments.backend_parity

The CPU backend is what the phone runs, so any difference is desktop output the phone would not
reproduce.
"""

import difflib
import gc
import statistics
import sys

from experiments import paths
from stt_button import config
from stt_button.cleaner import Cleaner


def main() -> int:
    source = paths.ASR / config.load()["recognizer"]
    transcripts = {p.stem: p.read_text().strip() for p in sorted(source.glob("*.txt"))}
    if not transcripts:
        sys.exit(f"no transcripts in {source}")
    outputs, timings = {}, {}
    for backend in ("llama", "ollama"):
        cleaner = Cleaner(backend=backend)
        cleaner.clean("Warm up.")
        outputs[backend], timings[backend] = {}, []
        for name, text in transcripts.items():
            result = cleaner.clean(text)
            outputs[backend][name] = result.text
            timings[backend].append((result.in_tokens, result.elapsed_ms))
        cleaner.release()
        del cleaner
        gc.collect()

    differing = [n for n in transcripts if outputs["llama"][n] != outputs["ollama"][n]]
    for backend, rows in timings.items():
        per_k = statistics.median(1000 * ms / max(1, tokens) for tokens, ms in rows)
        print(f"{backend:7} median {statistics.median(ms for _, ms in rows):6.0f} ms per transcript, "
              f"{per_k / 1000:.1f} s per 1000 input tokens")
    print(f"identical outputs: {len(transcripts) - len(differing)} of {len(transcripts)}")
    for name in differing:
        a, b = outputs["llama"][name].split(), outputs["ollama"][name].split()
        changes = [f"[{' '.join(a[i1:i2])} -> {' '.join(b[j1:j2])}]"
                   for op, i1, i2, j1, j2 in difflib.SequenceMatcher(a=a, b=b, autojunk=False).get_opcodes()
                   if op != "equal"]
        print(f"  {name}: {' '.join(changes)[:300]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
