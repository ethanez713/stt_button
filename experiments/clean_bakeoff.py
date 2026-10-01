"""Run both cleaner arms over the chosen recognizer's ramble transcripts; resumable.

    STT_MODEL_DIR=~/models/stt_button pyrun python -m experiments.clean_bakeoff [--qwen-backend llama|ollama]

The recognizer is config/pipeline.json's `recognizer`, which phase 03 sets.
"""

import argparse
import gc
import json
import sys
import time
import urllib.request

from experiments import paths
from stt_button import cleaner, config

ARMS = ["s1-mini", "qwen3.5-2b"]
OLLAMA_URL = "http://localhost:11434/api/chat"
OLLAMA_MODEL = "qwen3.5:2b"
# Ollama exposes no tokenizer, so its chunking and token counts use a words-to-tokens estimate.
TOKENS_PER_WORD = 1.4


class OllamaCleaner:
    name = "qwen3.5-2b"

    def __init__(self):
        cfg = config.load()
        self.spec = cfg["cleaners"][self.name]
        self.chunk_min_tokens = cfg["chunk_min_tokens"]
        self.chunk_max_tokens = cfg["chunk_max_tokens"]

    @staticmethod
    def count_tokens(text: str) -> int:
        return round(len(text.split()) * TOKENS_PER_WORD)

    def clean(self, raw: str) -> cleaner.Cleaned:
        started = time.monotonic()
        chunks = cleaner.live_chunks(raw, self.count_tokens, self.chunk_min_tokens, self.chunk_max_tokens, final=True)
        outputs, in_tokens, out_tokens = [], 0, 0
        for chunk in chunks:
            n_in = self.count_tokens(chunk)
            body = {
                "model": OLLAMA_MODEL,
                "messages": [
                    {"role": "system", "content": self.spec["system_prompt"]},
                    {"role": "user", "content": cleaner.user_turn(self.spec["control_line"], chunk)},
                ],
                "think": False,
                "stream": False,
                "options": {
                    "temperature": 0,
                    "repeat_penalty": 1.0,
                    "num_predict": cleaner.max_new_tokens(n_in, self.spec["max_new_tokens_ratio"],
                                                          self.spec["max_new_tokens_pad"]),
                },
            }
            request = urllib.request.Request(OLLAMA_URL, json.dumps(body).encode(),
                                             {"Content-Type": "application/json"})
            with urllib.request.urlopen(request, timeout=600) as response:
                reply = json.load(response)
            outputs.append(reply["message"]["content"].strip())
            in_tokens += n_in
            out_tokens += reply.get("eval_count", 0)
        return cleaner.Cleaned("\n\n".join(o for o in outputs if o), int((time.monotonic() - started) * 1000),
                               len(chunks), in_tokens, out_tokens)


def load_arm(arm: str, qwen_backend: str):
    if arm == "qwen3.5-2b" and qwen_backend == "ollama":
        return OllamaCleaner(), "ollama"
    try:
        return cleaner.Cleaner(arm), "llama.cpp"
    except (ValueError, RuntimeError) as err:
        if arm != "qwen3.5-2b":
            raise
        # llama-cpp-python raises ValueError when its bundled llama.cpp cannot load the architecture.
        print(f"qwen3.5-2b failed to load in llama-cpp-python ({err}); falling back to Ollama. "
              "The phone's llama.cpp support for Qwen3.5 is unproven until this loads.", flush=True)
        return OllamaCleaner(), "ollama"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--qwen-backend", choices=["llama", "ollama"], default="llama")
    args = parser.parse_args()

    recognizer = config.load()["recognizer"]
    source = paths.ASR / recognizer
    rambles = sorted(s["id"] for s in paths.sidecars() if s["mode"] == "ramble")
    transcripts = {i: (source / f"{i}.txt").read_text().strip() for i in rambles if (source / f"{i}.txt").exists()}
    if not transcripts:
        sys.exit(f"no ramble transcripts in {source}; run experiments.asr_bakeoff first")
    done = {(r["id"], r["arm"]) for r in paths.read_jsonl(paths.CLEAN_RESULTS)}

    for arm in ARMS:
        todo = [i for i in transcripts if (i, arm) not in done]
        if not todo:
            continue
        print(f"{arm}: loading", flush=True)
        model, backend = load_arm(arm, args.qwen_backend)
        out_dir = paths.CLEAN / arm
        out_dir.mkdir(parents=True, exist_ok=True)
        for i in todo:
            result = model.clean(transcripts[i])
            (out_dir / f"{i}.txt").write_text(result.text + "\n")
            paths.append_jsonl(paths.CLEAN_RESULTS, {
                "id": i, "arm": arm, "backend": backend, "recognizer": recognizer,
                "in_tokens": result.in_tokens, "out_tokens": result.out_tokens,
                "chunks": result.chunks, "elapsed_ms": result.elapsed_ms,
            })
            print(f"  {i}: {result.elapsed_ms} ms, {result.chunks} chunk(s)", flush=True)
        del model
        gc.collect()
    return 0


if __name__ == "__main__":
    sys.exit(main())
