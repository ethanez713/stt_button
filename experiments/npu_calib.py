"""Write the S1-mini NPU export's calibration set from the recognizer's transcripts.

    pyrun python -m experiments.npu_calib

Each sample is the exact cleaner prompt the phone sends. Where the CPU GGUF has cleaned that
transcript, its output is the assistant turn, so calibration also covers generation. The output
is ExecuTorch's `--calib_samples` format and stays under data/, which is gitignored.
"""

import json

from experiments import paths
from stt_button import config
from stt_button.cleaner import user_turn

OUT = paths.EXPERIMENTS / "npu" / "s1_mini_calib.json"


def main() -> int:
    pipeline = config.load()
    spec = pipeline["cleaners"]["s1-mini"]
    cleaned_dir = paths.CLEAN / "s1-mini"
    samples = []
    for transcript in sorted((paths.ASR / pipeline["recognizer"]).glob("*.txt")):
        messages = [
            {"role": "system", "content": spec["system_prompt"]},
            {"role": "user", "content": user_turn(spec["control_line"], transcript.read_text().strip())},
        ]
        cleaned = cleaned_dir / transcript.name
        if cleaned.exists():
            messages.append({"role": "assistant", "content": cleaned.read_text().strip()})
        samples.append({"messages": messages})
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(samples, indent=1))
    with_answer = sum(len(s["messages"]) == 3 for s in samples)
    print(f"{len(samples)} samples ({with_answer} with the GGUF's answer) -> {OUT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
