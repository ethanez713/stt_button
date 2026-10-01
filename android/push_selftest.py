"""Stages inputs for the debug SelfTest receiver in the app's files/selftest.

    pyrun python android/push_selftest.py --clean r05 r10 m07 --wav r05 s02

--clean renders the transcript data/experiments/asr/<recognizer>/<id>*.txt into the cleaner prompt the
phone benchmark used, with its token limit. --wav pushes data/recordings/<id>*.wav.
"""

import argparse
import glob
import subprocess
import sys
import tempfile
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO))

from stt_button import config  # noqa: E402
from stt_button.cleaner import max_new_tokens, render_prompt, user_turn  # noqa: E402

PKG = "com.ezhou.sttbutton"
STAGE = "/data/local/tmp/stt_selftest"


def one(pattern: str) -> Path:
    matches = glob.glob(str(REPO / pattern))
    if len(matches) != 1:
        sys.exit(f"expected one match for {pattern}, found {len(matches)}")
    return Path(matches[0])


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--clean", nargs="*", default=[])
    parser.add_argument("--wav", nargs="*", default=[])
    args = parser.parse_args()

    from llama_cpp import Llama

    cfg = config.load()
    spec = cfg["cleaners"]["s1-mini"]
    vocab = Llama(model_path=str(config.model_dir() / spec["model"]), vocab_only=True, verbose=False)
    serial = subprocess.run([str(Path(__file__).with_name("test_device.sh"))], check=True, capture_output=True,
                            text=True).stdout.strip()
    adb = lambda *a: subprocess.run(["adb", "-s", serial, *a], check=True, capture_output=True, text=True)

    with tempfile.TemporaryDirectory() as tmp:
        staged = Path(tmp) / "selftest"
        staged.mkdir()
        for name in args.clean:
            transcript = one(f"data/experiments/asr/{cfg['recognizer']}/{name}-*.txt").read_text().strip()
            tokens = len(vocab.tokenize(transcript.encode(), add_bos=False, special=False))
            (staged / f"{name}.prompt.txt").write_text(
                render_prompt(spec["system_prompt"], user_turn(spec["control_line"], transcript)))
            (staged / f"{name}.max").write_text(
                str(max_new_tokens(tokens, spec["max_new_tokens_ratio"], spec["max_new_tokens_pad"])))
        for name in args.wav:
            (staged / f"{name}.wav").write_bytes(one(f"data/recordings/{name}-*.wav").read_bytes())
        adb("shell", f"rm -rf {STAGE}")
        adb("push", str(staged), STAGE)
    adb("shell", f"run-as {PKG} rm -rf files/selftest && run-as {PKG} cp -r {STAGE} files/selftest; rm -rf {STAGE}")
    print(f"staged {len(args.clean)} prompts and {len(args.wav)} wavs")
    return 0


if __name__ == "__main__":
    sys.exit(main())
