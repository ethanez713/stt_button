import json
import re
import time
import urllib.request
from collections.abc import Callable
from dataclasses import dataclass

from stt_button import config
from stt_button.spoken import spoken_punctuation

# What Qwen3's chat template emits for an assistant turn with enable_thinking=False. Rendering it
# by hand keeps the prompt independent of how a binding passes template kwargs.
ASSISTANT_NO_THINK = "<|im_start|>assistant\n<think>\n\n</think>\n\n"
END_OF_TURN = "<|im_end|>"
OLLAMA_URL = "http://localhost:11434/api/generate"
# Upper bound on how long Ollama keeps the model on the GPU if a worker dies before releasing it.
OLLAMA_KEEP_ALIVE = "10m"


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


def _pieces(sentences: list[str], count_tokens: Callable[[str], int], max_tokens: int) -> list[str]:
    pieces = []
    for sentence in sentences:
        if count_tokens(sentence) <= max_tokens:
            pieces.append(sentence)
        else:
            pieces.extend(_split_words(sentence, count_tokens, max_tokens))
    return pieces


def live_chunks(text: str, count_tokens: Callable[[str], int], min_tokens: int, max_tokens: int,
                final: bool) -> list[str]:
    """Chunks of whole sentences, each closed once it holds min_tokens or more.

    A sentence over max_tokens is split at word boundaries first. With final=False the last
    sentence is withheld, since the next slice may continue it, and an unclosed remainder is not
    returned; the chunks are then a prefix of what final=True returns for any longer text that
    starts with the same words.
    """
    sentences = split_sentences(text)
    if not final:
        sentences = sentences[:-1]
    chunks = []
    current = ""
    for piece in _pieces(sentences, count_tokens, max_tokens):
        current = f"{current} {piece}" if current else piece
        if count_tokens(current) >= min_tokens:
            chunks.append(current)
            current = ""
    if final and current:
        chunks.append(current)
    return chunks


def matched_cue(text: str, cues: list[str]) -> str | None:
    spoken = re.sub(r"[^\w\s]", "", text.lower())
    return next((cue for cue in cues
                 if re.search(r"(?<![a-z])" + r"\s+".join(map(re.escape, cue.split())) + r"(?![a-z])", spoken)), None)


def skips_cleaner(text: str, cfg: dict) -> bool:
    return len(text.split()) <= cfg["skip_max_words"] and matched_cue(text, cfg["self_correction_cues"]) is None


def casual(text: str, cfg: dict) -> str:
    """Short takes are messages, where the commas and the final period are noise: drop them
    (commas inside numbers stay), and leave everything else as it was."""
    if len(text.split()) > cfg["skip_max_words"]:
        return text
    text = re.sub(r"(?<![0-9]),|,(?![0-9])", "", text)
    return re.sub(r"(?<!\.)\.(\s*)$", r"\1", text)


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


def _ollama(body: dict, timeout: float = 120) -> dict:
    request = urllib.request.Request(OLLAMA_URL, json.dumps(body).encode(), {"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.load(response)


def _ollama_stream(body: dict, cancelled: Callable[[], bool], timeout: float = 120) -> tuple[str, int] | None:
    """Generate while checking cancelled() after each token; closing the connection stops Ollama."""
    request = urllib.request.Request(OLLAMA_URL, json.dumps({**body, "stream": True}).encode(),
                                     {"Content-Type": "application/json"})
    text, tokens = "", 0
    with urllib.request.urlopen(request, timeout=timeout) as response:
        for line in response:
            if cancelled():
                return None
            part = json.loads(line)
            text += part["response"]
            tokens = part.get("eval_count", tokens + 1)
    return text, tokens


class Cleaner:
    """A loaded cleaner. backend "llama" runs llama.cpp on the CPU in this process, as the phone
    does; "ollama" runs the same GGUF on the local Ollama server's GPU, sending the same rendered
    prompt in raw mode so Ollama's own template never touches it.
    """

    def __init__(self, name: str | None = None, backend: str = "llama"):
        from llama_cpp import Llama

        cfg = config.load()
        self.name = name or cfg["cleaner"]
        self.spec = cfg["cleaners"][self.name]
        self.cfg = cfg
        model = config.model_dir() / self.spec["model"]
        if not model.is_file():
            raise FileNotFoundError(f"cleaner model missing: {model}")
        self.backend = backend
        if backend == "ollama":
            # Only the vocabulary, for chunking; Ollama has no tokenize endpoint.
            self._llm = Llama(model_path=str(model), vocab_only=True, verbose=False)
            self.ollama_model = self.spec["ollama_model"]
            _ollama({"model": self.ollama_model, "keep_alive": OLLAMA_KEEP_ALIVE})  # loads it onto the GPU
        else:
            self._llm = Llama(model_path=str(model), n_ctx=4096, n_threads=8, verbose=False)

    def release(self) -> None:
        """Unload from the GPU now; the in-process backend is freed with the object."""
        if self.backend == "ollama":
            _ollama({"model": self.ollama_model, "keep_alive": 0})

    def count_tokens(self, text: str) -> int:
        return len(self._llm.tokenize(text.encode(), add_bos=False, special=False))

    def clean_chunk(self, chunk: str, cancelled: Callable[[], bool] | None = None) -> tuple[str, int] | None:
        """The cleaned text of one chunk and the tokens generated, or None when cancelled() turned
        true while generating."""
        chunk = spoken_punctuation(chunk)
        prompt = render_prompt(self.spec["system_prompt"], user_turn(self.spec["control_line"], chunk))
        limit = max_new_tokens(self.count_tokens(chunk), self.spec["max_new_tokens_ratio"],
                               self.spec["max_new_tokens_pad"])
        generated = self._generate(prompt, limit, cancelled)
        return None if generated is None else (generated[0].strip(), generated[1])

    def clean(self, raw: str, skip: bool = True, min_tokens: int | None = None) -> Cleaned:
        """skip=False cleans even a take the skip rule would insert as recognized; min_tokens
        replaces the configured chunk size, which experiments use to compare against a whole pass."""
        started = time.monotonic()
        if skip and skips_cleaner(raw, self.cfg):
            return Cleaned(casual(spoken_punctuation(raw), self.cfg), int((time.monotonic() - started) * 1000), 0, 0, 0)
        outputs = []
        in_tokens = out_tokens = 0
        chunks = live_chunks(raw, self.count_tokens, min_tokens or self.cfg["chunk_min_tokens"],
                             self.cfg["chunk_max_tokens"], final=True)
        for chunk in chunks:
            text, n_out = self.clean_chunk(chunk)
            outputs.append(text)
            in_tokens += self.count_tokens(chunk)
            out_tokens += n_out
        # A chunk boundary is a sentence end; a paragraph break there cannot glue a list onto prose.
        text = "\n\n".join(o for o in outputs if o)
        return Cleaned(casual(text, self.cfg) if skip else text, int((time.monotonic() - started) * 1000),
                       len(chunks), in_tokens, out_tokens)

    def _generate(self, prompt: str, max_tokens: int,
                  cancelled: Callable[[], bool] | None = None) -> tuple[str, int] | None:
        # Both backends decode greedily with repeat_penalty 1.0: the bindings default to 1.1, and
        # the model cards specify plain greedy decoding. Only a cancellable call streams.
        if self.backend == "ollama":
            body = {
                "model": self.ollama_model, "prompt": prompt, "raw": True, "stream": False,
                "keep_alive": OLLAMA_KEEP_ALIVE,
                "options": {"temperature": 0, "repeat_penalty": 1.0, "num_predict": max_tokens,
                            "num_ctx": 4096, "stop": [END_OF_TURN]},
            }
            if cancelled is not None:
                return _ollama_stream(body, cancelled)
            reply = _ollama(body)
            return reply["response"], reply.get("eval_count", 0)
        tokens = self._llm.tokenize(prompt.encode(), add_bos=False, special=True)
        if cancelled is None:
            result = self._llm.create_completion(tokens, max_tokens=max_tokens, temperature=0.0,
                                                 repeat_penalty=1.0, stop=[END_OF_TURN])
            return result["choices"][0]["text"], result["usage"]["completion_tokens"]
        text, count = "", 0
        for part in self._llm.create_completion(tokens, max_tokens=max_tokens, temperature=0.0,
                                                repeat_penalty=1.0, stop=[END_OF_TURN], stream=True):
            if cancelled():
                return None
            text += part["choices"][0]["text"]
            count += 1
        return text, count


def clean(raw: str, cleaner: str | None = None) -> Cleaned:
    return Cleaner(cleaner).clean(raw)
