import re
import time
from collections.abc import Callable
from dataclasses import dataclass

from stt_button import config

# What Qwen3's chat template emits for an assistant turn with enable_thinking=False. Rendering it
# by hand keeps the prompt independent of how a binding passes template kwargs.
ASSISTANT_NO_THINK = "<|im_start|>assistant\n<think>\n\n</think>\n\n"
END_OF_TURN = "<|im_end|>"


@dataclass
class Cleaned:
    text: str
    elapsed_ms: int
    chunks: int
    in_tokens: int
    out_tokens: int


def render_prompt(system_prompt: str, user_turn: str) -> str:
    return (
        f"<|im_start|>system\n{system_prompt}{END_OF_TURN}\n"
        f"<|im_start|>user\n{user_turn}{END_OF_TURN}\n"
        f"{ASSISTANT_NO_THINK}"
    )


def user_turn(control_line: str | None, chunk: str) -> str:
    return f"{control_line}\n{chunk}" if control_line else chunk


def max_new_tokens(input_tokens: int, ratio: float, pad: int) -> int:
    return int(ratio * input_tokens + pad)


def split_sentences(text: str) -> list[str]:
    return [s for s in re.split(r"(?<=[.!?])\s+", text.strip()) if s]


def chunk_transcript(text: str, count_tokens: Callable[[str], int], limit: int) -> list[str]:
    """Fewest in-order chunks of whole sentences, each within limit tokens.

    A sentence longer than the limit on its own is split at word boundaries.
    """
    pieces = []
    for sentence in split_sentences(text):
        if count_tokens(sentence) <= limit:
            pieces.append(sentence)
        else:
            pieces.extend(_split_words(sentence, count_tokens, limit))
    chunks = []
    current = ""
    for piece in pieces:
        candidate = f"{current} {piece}" if current else piece
        if current and count_tokens(candidate) > limit:
            chunks.append(current)
            current = piece
        else:
            current = candidate
    if current:
        chunks.append(current)
    return chunks


def _split_words(sentence: str, count_tokens: Callable[[str], int], limit: int) -> list[str]:
    parts = []
    current = ""
    for word in sentence.split():
        candidate = f"{current} {word}" if current else word
        if current and count_tokens(candidate) > limit:
            parts.append(current)
            current = word
        else:
            current = candidate
    if current:
        parts.append(current)
    return parts


class Cleaner:
    def __init__(self, name: str | None = None):
        from llama_cpp import Llama

        cfg = config.load()
        self.name = name or cfg["cleaner"]
        self.spec = cfg["cleaners"][self.name]
        self.chunk_max_tokens = cfg["chunk_max_tokens"]
        model = config.model_dir() / self.spec["model"]
        if not model.is_file():
            raise FileNotFoundError(f"cleaner model missing: {model}")
        self._llm = Llama(model_path=str(model), n_ctx=4096, n_threads=8, verbose=False)

    def count_tokens(self, text: str) -> int:
        return len(self._llm.tokenize(text.encode(), add_bos=False, special=False))

    def clean(self, raw: str) -> Cleaned:
        started = time.monotonic()
        outputs = []
        in_tokens = out_tokens = 0
        chunks = chunk_transcript(raw, self.count_tokens, self.chunk_max_tokens)
        for chunk in chunks:
            prompt = render_prompt(self.spec["system_prompt"], user_turn(self.spec["control_line"], chunk))
            tokens = self._llm.tokenize(prompt.encode(), add_bos=False, special=True)
            n_in = self.count_tokens(chunk)
            result = self._llm.create_completion(
                tokens,
                max_tokens=max_new_tokens(n_in, self.spec["max_new_tokens_ratio"], self.spec["max_new_tokens_pad"]),
                temperature=0.0,
                # llama-cpp-python defaults to repeat_penalty 1.1; the model cards specify plain greedy decoding.
                repeat_penalty=1.0,
                stop=[END_OF_TURN],
            )
            outputs.append(result["choices"][0]["text"].strip())
            in_tokens += n_in
            out_tokens += result["usage"]["completion_tokens"]
        # A chunk boundary is a sentence end; a paragraph break there cannot glue a list onto prose.
        return Cleaned("\n\n".join(o for o in outputs if o), int((time.monotonic() - started) * 1000),
                       len(chunks), in_tokens, out_tokens)


def clean(raw: str, cleaner: str | None = None) -> Cleaned:
    return Cleaner(cleaner).clean(raw)
