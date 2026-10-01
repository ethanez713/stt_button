"""Phone recognizer speeds and CPU contention, through adb with the release command-line binaries.

    STT_MODEL_DIR=~/models/stt_button pyrun python -m experiments.phone_speeds stage
    pyrun python -m experiments.phone_speeds windows      [--reps 5]
    pyrun python -m experiments.phone_speeds contention   [--reps 3]
    pyrun python -m experiments.phone_speeds unstage

`stage` pushes, to /data/local/tmp/speeds:
- sherpa-onnx 1.13.8's android-aarch64-termux-static binaries and the NDK r26c libc++_shared.so;
- the int8 Unified and FastConformer 480 ms models;
- llama.cpp b11264's llama-speculative-simple (built per plan 09) and the S1-mini GGUF;
- 2/5/10/30 s windows cut from m02 at 20 s;
- r07's rendered cleaner prompt.

`windows` times Parakeet (6 threads) on each window.

`contention` runs each of these alone and then together, and prints their speeds:
- Parakeet on the 30 s window at 6 threads;
- S1-mini prompt lookup on r07 at 6 threads;
- FastConformer at 2 threads, looped back to back.

The screen is held on while either runs, because a dozing phone's clocks swing results by 3x.
"""

import argparse
import subprocess
import sys
import tempfile
import wave
from pathlib import Path

import numpy as np

from experiments import paths
from experiments.phone_cleaner import adb
from stt_button import config
from stt_button.asr import SAMPLE_RATE, read_wav
from stt_button.cleaner import render_prompt, user_turn

PHONE_DIR = "/data/local/tmp/speeds"
SHERPA_BIN = Path.home() / "tools/sherpa-android/sherpa-onnx-v1.13.8-android-aarch64-termux-static/bin"
LLAMA_BIN = Path.home() / "tools/llama.cpp/build-android/bin/llama-speculative-simple"
LIBCXX = (Path.home() / "Android/Sdk/ndk/26.2.11394342/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib"
          "/aarch64-linux-android/libc++_shared.so")
WINDOWS_S = (2, 5, 10, 30)
WINDOW_SOURCE, WINDOW_START_S = "m02", 20
PROMPT = "r07"

SHELL_FUNCTIONS = f"""
cd {PHONE_DIR}
export LD_LIBRARY_PATH={PHONE_DIR}
transducer() {{ echo --tokens=$1/tokens.txt --encoder=$1/encoder.int8.onnx --decoder=$1/decoder.int8.onnx --joiner=$1/joiner.int8.onnx; }}
asr() {{ ./sherpa-onnx-offline $(transducer unified) --model-type=nemo_transducer --num-threads=6 \\
  --decoding-method=greedy_search win/w$1.wav 2>&1 | grep -o "Elapsed seconds: [0-9.]*" | sed "s/^/asr w$1 /"; }}
cln() {{ ./llama-speculative-simple -m s1-mini.gguf -f prompt.txt -n 160 --temp 0 --repeat-penalty 1.0 -c 2048 -t 6 \\
  --spec-type ngram-simple --spec-ngram-simple-size-n 3 --spec-ngram-simple-size-m 16 </dev/null 2>&1 >/dev/null \\
  | grep -E "encoded|decoded" | sed "s/^.* I /cln /"; }}
stream() {{ ./sherpa-onnx $(transducer fc) --num-threads=2 win/w30.wav 2>&1 | grep -o "RTF) = .*" | sed "s/^/stream /"; }}
stream_loop() {{ while [ ! -f stop_stream ]; do stream; done; }}
"""


def windows_script(reps: int) -> str:
    return "".join(f"for i in $(seq {reps}); do asr {w:02d}; done\n" for w in WINDOWS_S)


def contention_script(reps: int) -> str:
    return f"""rm -f stop_stream
echo "== alone"; stream; for i in $(seq {reps}); do asr 30; cln; done
echo "== parakeet + cleaner"; for i in $(seq {reps}); do asr 30 & A=$!; cln; wait $A; done
echo "== all three"; stream_loop & S=$!; sleep 2
for i in $(seq {reps}); do asr 30 & A=$!; cln; wait $A; done
touch stop_stream; wait $S; rm -f stop_stream
"""


def serial() -> str:
    return subprocess.run(["aconnect", "serial"], check=True, capture_output=True, text=True).stdout.strip()


def run_held_awake(device: str, script: str) -> None:
    adb(device, "shell", "svc power stayon true; input keyevent KEYCODE_WAKEUP")
    try:
        result = subprocess.run([str(Path.home() / "Android/Sdk/platform-tools/adb"), "-s", device, "shell", "sh"],
                                input=SHELL_FUNCTIONS + script, capture_output=True, text=True, check=True)
        print(result.stdout, end="")
    finally:
        adb(device, "shell", "svc power stayon false")


def stage(device: str) -> None:
    pipeline = config.load()
    models = config.model_dir()
    spec = pipeline["cleaners"]["s1-mini"]
    recording = next(m["id"] for m in paths.sidecars() if m["id"].startswith(WINDOW_SOURCE))
    samples = read_wav(paths.RECORDINGS / f"{recording}.wav")
    transcript = next((paths.ASR / pipeline["recognizer"]).glob(f"{PROMPT}-*.txt")).read_text().strip()
    with tempfile.TemporaryDirectory() as tmp:
        staged = Path(tmp)
        (staged / "win").mkdir()
        for seconds in WINDOWS_S:
            start = WINDOW_START_S * SAMPLE_RATE
            pcm = (samples[start:start + seconds * SAMPLE_RATE] * 32767).astype(np.int16)
            with wave.open(str(staged / "win" / f"w{seconds:02d}.wav"), "wb") as w:
                w.setnchannels(1)
                w.setsampwidth(2)
                w.setframerate(SAMPLE_RATE)
                w.writeframes(pcm.tobytes())
        # llama.cpp drops one trailing newline from -f files; the prompt must keep its own.
        (staged / "prompt.txt").write_text(
            render_prompt(spec["system_prompt"], user_turn(spec["control_line"], transcript)) + "\n")
        adb(device, "shell", f"mkdir -p {PHONE_DIR}/unified {PHONE_DIR}/fc")
        adb(device, "push", str(staged / "win"), str(staged / "prompt.txt"), PHONE_DIR)
    for binary in (SHERPA_BIN / "sherpa-onnx-offline", SHERPA_BIN / "sherpa-onnx", LLAMA_BIN, LIBCXX):
        adb(device, "push", str(binary), PHONE_DIR)
    adb(device, "push", str(models / spec["model"]), f"{PHONE_DIR}/s1-mini.gguf")
    for model_dir, target in ((models / pipeline["recognizer"], "unified"), (models / "fc-stream480", "fc")):
        for name in ("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt"):
            adb(device, "push", str(model_dir / name), f"{PHONE_DIR}/{target}/")
    adb(device, "shell", f"chmod 755 {PHONE_DIR}/sherpa-onnx* {PHONE_DIR}/llama-speculative-simple")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("action", choices=["stage", "windows", "contention", "unstage"])
    parser.add_argument("--reps", type=int)
    args = parser.parse_args()
    device = serial()
    if args.action == "stage":
        stage(device)
    elif args.action == "windows":
        run_held_awake(device, windows_script(args.reps or 5))
    elif args.action == "contention":
        run_held_awake(device, contention_script(args.reps or 3))
    else:
        adb(device, "shell", f"rm -rf {PHONE_DIR}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
