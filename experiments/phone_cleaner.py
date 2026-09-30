"""Clean every transcript on the phone's CPU and compare outputs and latency across decoding modes.

    STT_MODEL_DIR=~/models/stt_button pyrun python -m experiments.phone_cleaner \
        [--backends cpu lookup] [--reps 3] [--threads 6] [--lookup-n 3 --lookup-m 16] [--only NAME...]

"cpu" is llama.cpp's llama-completion with the Q4_K_M GGUF. "lookup" is the same model under
prompt-lookup decoding: llama-speculative-simple with --spec-type ngram-simple, which drafts the
tokens that followed the last n tokens' previous occurrence in the prompt, and keeps the prefix the
model agrees with. The binaries and GGUF are staged in /data/local/tmp/llamacpp beforehand
(plan 09 records the build). Prompts are rendered here by stt_button.cleaner.render_prompt.
"""

import argparse
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
PHONE_DIR = "/data/local/tmp/llamacpp"
GGUF = "s1-mini-q4_k_m.gguf"
OUT = paths.EXPERIMENTS / "phone_clean"
RUNS = OUT / "runs.jsonl"
# Both binaries print the whole rendered prompt first when they echo it; the generation follows
# the empty think block that ends every cleaner prompt.
ASSISTANT_START = "</think>\n\n"


def adb(serial: str, *args: str) -> str:
    return subprocess.run([str(ADB), "-s", serial, *args], check=True, capture_output=True, text=True).stdout


def run_on_phone(serial: str, command: str) -> tuple[str, str]:
    """Run command in the phone's llama.cpp directory; return its stdout and stderr."""
    adb(serial, "shell", f"cd {PHONE_DIR} && {command} < /dev/null > out.txt 2> run.log")
    return adb(serial, "shell", f"cat {PHONE_DIR}/out.txt"), adb(serial, "shell", f"cat {PHONE_DIR}/run.log")


def run_cpu(serial: str, name: str, limit: int, args) -> dict:
    out, log = run_on_phone(serial,
        f"./llama-completion -m {GGUF} -f prompts/{name}.txt -n {limit} --temp 0 --repeat-penalty 1.0 "
        f"-c 2048 -t {args.threads} -no-cnv --no-display-prompt")
    ms = lambda pattern: round(float(re.search(pattern, log).group(1)))
    return {
        "text": out.replace("[end of text]", "").strip(),
        "out_tokens": int(re.search(r"common_perf_print: +eval time = +[\d.]+ ms / +(\d+) runs", log).group(1)),
        "prefill_ms": ms(r"common_perf_print: prompt eval time = +([\d.]+)"),
        "decode_ms": ms(r"common_perf_print: +eval time = +([\d.]+)"),
    }


def run_lookup(serial: str, name: str, limit: int, args) -> dict:
    out, log = run_on_phone(serial,
        f"./llama-speculative-simple -m {GGUF} -f prompts/{name}.txt -n {limit} --temp 0 --repeat-penalty 1.0 "
        f"-c 2048 -t {args.threads} --spec-type ngram-simple "
        f"--spec-ngram-simple-size-n {args.lookup_n} --spec-ngram-simple-size-m {args.lookup_m}")
    seconds = lambda label: re.search(rf"{label} +(\d+) tokens in +([\d.]+) seconds", log).groups()
    _, encode_s = seconds("encoded")
    out_tokens, decode_s = seconds("decoded")
    drafted, accepted = (int(re.search(rf"{k} *= (\d+)", log).group(1)) for k in ("n_drafted", "n_accept"))
    return {
        "text": out.rsplit(ASSISTANT_START, 1)[1].split(END_OF_TURN)[0].strip(),
        "out_tokens": int(out_tokens), "prefill_ms": round(1000 * float(encode_s)),
        "decode_ms": round(1000 * float(decode_s)), "drafted": drafted, "accepted": accepted,
        "lookup_n": args.lookup_n, "lookup_m": args.lookup_m,
    }


BACKENDS = {"cpu": run_cpu, "lookup": run_lookup}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--backends", nargs="+", default=["cpu", "lookup"], choices=BACKENDS)
    parser.add_argument("--reps", type=int, default=3)
    parser.add_argument("--threads", type=int, default=6)
    parser.add_argument("--lookup-n", type=int, default=3)
    parser.add_argument("--lookup-m", type=int, default=16)
    parser.add_argument("--only", nargs="+", help="transcript names to run, default all")
    args = parser.parse_args()

    from llama_cpp import Llama

    pipeline = config.load()
    spec = pipeline["cleaners"]["s1-mini"]
    vocab = Llama(model_path=str(config.model_dir() / spec["model"]), vocab_only=True, verbose=False)
    count_tokens = lambda text: len(vocab.tokenize(text.encode(), add_bos=False, special=False))
    source = paths.ASR / pipeline["recognizer"]
    transcripts = {p.stem: p.read_text().strip() for p in sorted(source.glob("*.txt"))
                   if not args.only or p.stem in args.only}
    if not transcripts:
        sys.exit(f"no transcripts in {source}")
    if max(count_tokens(t) for t in transcripts.values()) > pipeline["chunk_max_tokens"]:
        sys.exit("a transcript needs chunking; this script sends each transcript as one prompt")

    serial = subprocess.run(["aconnect", "serial"], check=True, capture_output=True, text=True).stdout.strip()
    limits = {}
    with tempfile.TemporaryDirectory() as tmp:
        staged = Path(tmp) / "prompts"
        staged.mkdir()
        for name, text in transcripts.items():
            prompt = render_prompt(spec["system_prompt"], user_turn(spec["control_line"], text))
            limits[name] = max_new_tokens(count_tokens(text), spec["max_new_tokens_ratio"],
                                          spec["max_new_tokens_pad"])
            # llama.cpp drops one trailing newline from -f files; the prompt must keep its own.
            (staged / f"{name}.txt").write_text(prompt + "\n")
        adb(serial, "push", str(staged), PHONE_DIR)

    outputs = {b: {} for b in args.backends}
    for rep in range(args.reps):
        for backend in args.backends:
            for name in transcripts:
                run = BACKENDS[backend](serial, name, limits[name], args)
                outputs[backend][name] = run.pop("text")
                (OUT / backend).mkdir(parents=True, exist_ok=True)
                (OUT / backend / f"{name}.txt").write_text(outputs[backend][name] + "\n")
                record = {"backend": backend, "transcript": name, "rep": rep, "threads": args.threads, **run}
                paths.append_jsonl(RUNS, record)
                print(record, flush=True)

    summarize(args, transcripts, outputs)
    return 0


def summarize(args, transcripts: dict, outputs: dict) -> None:
    runs = [r for r in paths.read_jsonl(RUNS) if r["transcript"] in transcripts]
    for backend in outputs:
        rows = [r for r in runs if r["backend"] == backend
                and (backend != "lookup" or (r["lookup_n"], r["lookup_m"]) == (args.lookup_n, args.lookup_m))]
        latency = sorted(r["prefill_ms"] + r["decode_ms"] for r in rows)
        decode = statistics.median(1000 * r["out_tokens"] / max(1, r["decode_ms"]) for r in rows)
        line = (f"{backend}: median {statistics.median(latency):.0f} ms, p90 {latency[int(0.9 * len(latency))]} ms, "
                f"max {latency[-1]} ms, decode {decode:.0f} tok/s")
        if backend == "lookup":
            line += f", accepted {sum(r['accepted'] for r in rows) / max(1, sum(r['drafted'] for r in rows)):.0%} of drafts"
        print(line)
    if {"cpu", "lookup"} <= outputs.keys():
        differing = [n for n in transcripts if outputs["cpu"][n] != outputs["lookup"][n]]
        print(f"identical outputs: {len(transcripts) - len(differing)} of {len(transcripts)}")
        for name in differing:
            print(f"  {name}: {word_changes(outputs['cpu'][name], outputs['lookup'][name])[:300]}")


if __name__ == "__main__":
    sys.exit(main())
