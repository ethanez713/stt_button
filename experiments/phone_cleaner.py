"""Clean every transcript on the phone with both cleaner backends and compare outputs and latency.

    STT_MODEL_DIR=~/models/stt_button pyrun python -m experiments.phone_cleaner [--reps 3] [--threads 4]

"npu" is ExecuTorch's qnn_llama_runner with the hybrid QNN export; "cpu" is llama.cpp's
llama-completion with the Q4_K_M GGUF. Both are staged on the phone beforehand (plan 09, step 5).
Prompts are rendered here by stt_button.cleaner.render_prompt. The NPU runner receives them
pre-tokenized because its built-in Qwen3 template differs from the cleaner's.
"""

import argparse
import json
import re
import statistics
import subprocess
import sys
import tempfile
from pathlib import Path

from experiments import paths
from experiments.backend_parity import word_changes
from stt_button import config
from stt_button.cleaner import END_OF_TURN, max_new_tokens, render_prompt, user_turn

ADB = Path.home() / "Android/Sdk/platform-tools/adb"
NPU_DIR = "/data/local/tmp/s1mini"
CPU_DIR = "/data/local/tmp/llamacpp"
NPU_PTE = "hybrid_llama_qnn.pte"
CPU_GGUF = "s1-mini-q4_k_m.gguf"
OUT = paths.EXPERIMENTS / "npu" / "phone"
RUNS = OUT / "runs.jsonl"


def adb(serial: str, *args: str) -> str:
    return subprocess.run([str(ADB), "-s", serial, *args], check=True, capture_output=True, text=True).stdout


def timed_shell(serial: str, directory: str, command: str) -> int:
    """Run command on the phone and return its on-device wall time in ms, which includes process
    start and model load, as a cold take would."""
    out = adb(serial, "shell", f"cd {directory} && s=$(date +%s%N); {command}; e=$(date +%s%N); "
                                f"echo $(( (e - s) / 1000000 ))")
    return int(out.split()[-1])


def run_npu(serial: str, name: str, prompt_tokens: int, limit: int) -> dict:
    wall = timed_shell(serial, NPU_DIR,
        f"LD_LIBRARY_PATH=. ADSP_LIBRARY_PATH=. ./qnn_llama_runner --model_path {NPU_PTE} "
        f"--tokenizer_path tokenizer.json --decoder_model_version qwen3 --eval_mode 1 --temperature 0 "
        f"--seq_len {prompt_tokens + limit} --tokenized_prompt prompts/{name}.bin --output_path out.txt --shared_buffer "
        f"> run.log 2>&1")
    log = adb(serial, "shell", f"cat {NPU_DIR}/run.log")
    stats = json.loads(re.search(r"PyTorchObserver (\{.*\})", log).group(1))
    return {
        "text": runner_text(adb(serial, "shell", f"cat {NPU_DIR}/out.txt"), f"prompts/{name}.bin"),
        "wall_ms": wall, "out_tokens": stats["generated_tokens"],
        "load_ms": stats["model_load_end_ms"] - stats["model_load_start_ms"],
        "prefill_ms": stats["prompt_eval_end_ms"] - stats["inference_start_ms"],
        "decode_ms": stats["inference_end_ms"] - stats["prompt_eval_end_ms"],
    }


def runner_text(output: str, prompt_arg: str) -> str:
    """qnn_llama_runner writes its prompt argument, then the generation up to and including <|im_end|>."""
    return output.removeprefix(prompt_arg).split(END_OF_TURN)[0].strip()


def run_cpu(serial: str, name: str, limit: int, threads: int) -> dict:
    wall = timed_shell(serial, CPU_DIR,
        f"./llama-completion -m {CPU_GGUF} -f prompts/{name}.txt -n {limit} --temp 0 --repeat-penalty 1.0 "
        f"-c 2048 -t {threads} -no-cnv --no-display-prompt < /dev/null > out.txt 2> run.log")
    log = adb(serial, "shell", f"cat {CPU_DIR}/run.log")
    ms = lambda pattern: round(float(re.search(pattern, log).group(1)))
    return {
        "text": adb(serial, "shell", f"cat {CPU_DIR}/out.txt").replace("[end of text]", "").strip(),
        "wall_ms": wall,
        "out_tokens": int(re.search(r"common_perf_print: +eval time = +[\d.]+ ms / +(\d+) runs", log).group(1)),
        "load_ms": ms(r"common_perf_print: +load time = +([\d.]+)"),
        "prefill_ms": ms(r"common_perf_print: prompt eval time = +([\d.]+)"),
        "decode_ms": ms(r"common_perf_print: +eval time = +([\d.]+)"),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--reps", type=int, default=3)
    parser.add_argument("--threads", type=int, default=4)
    parser.add_argument("--backends", nargs="+", default=["npu", "cpu"])
    args = parser.parse_args()

    from llama_cpp import Llama

    pipeline = config.load()
    spec = pipeline["cleaners"]["s1-mini"]
    vocab = Llama(model_path=str(config.model_dir() / spec["model"]), vocab_only=True, verbose=False)
    tokenize = lambda text, special: vocab.tokenize(text.encode(), add_bos=False, special=special)
    source = paths.ASR / pipeline["recognizer"]
    transcripts = {p.stem: p.read_text().strip() for p in sorted(source.glob("*.txt"))}
    if not transcripts:
        sys.exit(f"no transcripts in {source}")
    if max(len(tokenize(t, False)) for t in transcripts.values()) > pipeline["chunk_max_tokens"]:
        sys.exit("a transcript needs chunking; this script sends each transcript as one prompt")

    serial = subprocess.run(["aconnect", "serial"], check=True, capture_output=True, text=True).stdout.strip()
    prompts, limits = {}, {}
    with tempfile.TemporaryDirectory() as tmp:
        staged = Path(tmp) / "prompts"
        staged.mkdir()
        for name, text in transcripts.items():
            prompt = render_prompt(spec["system_prompt"], user_turn(spec["control_line"], text))
            prompts[name] = tokenize(prompt, True)
            limits[name] = max_new_tokens(len(tokenize(text, False)), spec["max_new_tokens_ratio"],
                                          spec["max_new_tokens_pad"])
            (staged / f"{name}.bin").write_bytes(b"".join(t.to_bytes(8, "little") for t in prompts[name]))
            # llama-completion drops one trailing newline from -f files; the prompt must keep its own.
            (staged / f"{name}.txt").write_text(prompt + "\n")
        for directory in (NPU_DIR, CPU_DIR):
            adb(serial, "push", str(staged), directory)

    outputs = {b: {} for b in args.backends}
    for rep in range(args.reps):
        for backend in args.backends:
            for name in transcripts:
                if backend == "npu":
                    run = run_npu(serial, name, len(prompts[name]), limits[name])
                else:
                    run = run_cpu(serial, name, limits[name], args.threads)
                outputs[backend][name] = run.pop("text")
                record = {"backend": backend, "transcript": name, "rep": rep,
                          "prompt_tokens": len(prompts[name]), **run}
                if backend == "cpu":
                    record["threads"] = args.threads
                paths.append_jsonl(RUNS, record)
                print(json.dumps(record), flush=True)

    for backend, texts in outputs.items():
        (OUT / backend).mkdir(parents=True, exist_ok=True)
        for name, text in texts.items():
            (OUT / backend / f"{name}.txt").write_text(text + "\n")
    summarize(transcripts, outputs)
    return 0


def summarize(transcripts: dict, outputs: dict) -> None:
    runs = paths.read_jsonl(RUNS)
    for backend in outputs:
        rows = [r for r in runs if r["backend"] == backend]
        med = lambda key: statistics.median(r[key] for r in rows)
        prefill = statistics.median(1000 * r["prompt_tokens"] / r["prefill_ms"] for r in rows)
        decode = statistics.median(1000 * r["out_tokens"] / max(1, r["decode_ms"]) for r in rows)
        print(f"{backend}: median wall {med('wall_ms'):.0f} ms, load {med('load_ms'):.0f} ms, "
              f"prefill {prefill:.0f} tok/s, decode {decode:.1f} tok/s")
    if {"npu", "cpu"} <= outputs.keys():
        differing = [n for n in transcripts if outputs["npu"][n] != outputs["cpu"][n]]
        print(f"identical outputs: {len(transcripts) - len(differing)} of {len(transcripts)}")
        for name in differing:
            print(f"  {name}: {word_changes(outputs['cpu'][name], outputs['npu'][name])[:300]}")


if __name__ == "__main__":
    sys.exit(main())
