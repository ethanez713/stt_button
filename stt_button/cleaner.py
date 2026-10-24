import json
import re
import time
import unicodedata
import urllib.request
from collections import Counter
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


def prompt_parts(system_prompt: str, control_line: str | None, chunk: str) -> tuple[str, str, str]:
    """render_prompt split around the transcript: (template before, transcript, template after). The
    template is tokenized with its special tokens and the transcript as plain text, so a transcript
    that spells out <|im_end|> cannot close the user turn. Line breaks that open the chunk go with the
    template, so the parts tokenize as the whole prompt does."""
    text = chunk.lstrip("\n")
    head = f"<|im_start|>system\n{system_prompt}{END_OF_TURN}\n<|im_start|>user\n{user_turn(control_line, '')}"
    return head + chunk[:len(chunk) - len(text)], text, f"{END_OF_TURN}\n{ASSISTANT_NO_THINK}"


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


_OPENING_FILLERS = {"uh", "um", "oh", "or", "and", "but", "so", "well", "okay"}
_JUDGEABLE_WORDS = 3


def _spoken_words(text: str) -> list[str]:
    return re.sub(r"[^\w\s]", "", text.lower()).split()


def opens_with_cue(sentence: str, cues: list[str]) -> bool:
    """Whether the sentence starts with a self-correction cue, after any filler words: the shape of
    a correction that reaches back into the sentence before it ("Actually, no, make that Wednesday")."""
    words = _spoken_words(sentence)
    while words and words[0] in _OPENING_FILLERS:
        words = words[1:]
    return any(words[:len(cue.split())] == cue.split() for cue in cues)


def is_correction_unit(chunk: str, cues: list[str]) -> bool:
    return any(opens_with_cue(sentence, cues) for sentence in split_sentences(chunk)[1:])


def unresolved_corrections(chunks: list[str], outputs: list[str], cues: list[str]) -> tuple[int, int]:
    """(correction units, those whose cleaned text still has a sentence opening with a cue): the
    second counts corrections the cleaner left standing, plus sentences that only looked like one."""
    units = [output for chunk, output in zip(chunks, outputs) if is_correction_unit(chunk, cues)]
    return len(units), sum(any(opens_with_cue(s, cues) for s in split_sentences(o)[1:]) for o in units)


def live_chunks(text: str, count_tokens: Callable[[str], int], min_tokens: int, max_tokens: int,
                final: bool, cues: list[str] | None = None) -> list[str]:
    """Chunks of whole sentences, each closed once it holds min_tokens or more.

    A sentence over max_tokens is split at word boundaries first. With cues, a sentence that opens
    with one is a correction: it and the sentence before it form a chunk of their own, because the
    cleaner resolves a correction more reliably with nothing else around it, and the chunk before
    closes early. With final=False the last sentence is withheld, since the next slice may continue
    it, and so is whatever that sentence could still join; an unclosed remainder is not returned.
    The chunks are then a prefix of what final=True returns for any longer text that starts with the
    same words.
    """
    sentences = split_sentences(text)
    withheld = None
    if not final:
        sentences, withheld = sentences[:-1], (sentences[-1] if sentences else None)
    units: list[tuple[str, bool]] = []
    for piece in _pieces(sentences, count_tokens, max_tokens):
        if cues and units and opens_with_cue(piece, cues):
            units[-1] = (f"{units[-1][0]} {piece}", True)
        else:
            units.append((piece, False))
    if cues and not final and units and (
            withheld is None or len(_spoken_words(withheld)) < _JUDGEABLE_WORDS or opens_with_cue(withheld, cues)):
        units.pop()
    chunks = []
    current = ""
    for unit, corrects in units:
        if corrects:
            chunks += [current, unit] if current else [unit]
            current = ""
            continue
        current = f"{current} {unit}" if current else unit
        if count_tokens(current) >= min_tokens:
            chunks.append(current)
            current = ""
    if final and current:
        chunks.append(current)
    return chunks


def join_cleaned(chunks: list[str], outputs: list[str], cues: list[str]) -> str:
    """Cleaned chunks as one text. A chunk boundary is a sentence end, and a paragraph break there
    cannot glue a list onto prose; a correction unit is cut out of the middle of a thought, so it
    joins its neighbours with a space unless a list ends just before the seam."""
    text, inline = "", False
    for chunk, output in zip(chunks, outputs):
        unit = is_correction_unit(chunk, cues)
        if output:
            after_list = text.rsplit("\n", 1)[-1].startswith("- ")
            text += ("" if not text else " " if (inline or unit) and not after_list else "\n\n") + output
        inline = unit
    return text


def matched_cue(text: str, cues: list[str]) -> str | None:
    spoken = re.sub(r"[^\w\s]", "", text.lower())
    return next((cue for cue in cues
                 if re.search(r"(?<![a-z])" + r"\s+".join(map(re.escape, cue.split())) + r"(?![a-z])", spoken)), None)


def skips_cleaner(text: str, cfg: dict) -> bool:
    return len(text.split()) <= cfg["skip_max_words"] and matched_cue(text, cfg["self_correction_cues"]) is None


_FIRST_PERSON = {"i", "i'm", "i'll", "i've", "i'd"}
_SENTENCE_END = re.compile(r"[.?!]$")
_DRIFT_MAX_EDITS = 2  # one for a token of three characters or fewer, where two edits can reach any other


def _fact_tokens(text: str) -> Counter[str]:
    """Tokens with a digit, and capitalized words that do not open a sentence, lower-cased with
    their edge punctuation stripped."""
    facts: Counter[str] = Counter()
    opens = True
    for word in text.split():
        token = re.sub(r"^[^\w]+|[^\w]+$", "", word)
        if token and token.lower() not in _FIRST_PERSON and (
                any(c.isdigit() for c in token) or (token[0].isupper() and not opens)):
            facts[token.lower()] += 1
        opens = bool(_SENTENCE_END.search(word))
    return facts


def _edits(a: str, b: str) -> int:
    row = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        previous, row[0] = row[0], i
        for j, cb in enumerate(b, 1):
            previous, row[j] = row[j], min(row[j] + 1, row[j - 1] + 1, previous + (ca != cb))
    return row[-1]


def fact_drift(raw: str, cleaned: str) -> list[tuple[str, str]]:
    """(lost, gained) pairs where the cleaner replaced a name or number with a near spelling of
    another, such as S20 for S25. A number respelled ("five" for 5) or a name lower-cased is not
    drift, and neither is a dropped token with no close new one. A token with a digit counts per
    occurrence, so a swap among tokens the raw text already held shows."""
    before, after = _fact_tokens(raw), _fact_tokens(cleaned)
    lost = [t for t in before if (after[t] < before[t] if any(c.isdigit() for c in t) else t not in after)]
    gained = [t for t in after if (after[t] > before[t] if any(c.isdigit() for c in t) else t not in before)]
    return [(m, n) for m in sorted(lost) for n in sorted(gained)
            if len(m) > 1 and len(n) > 1 and _edits(m, n) <= (_DRIFT_MAX_EDITS if min(len(m), len(n)) > 3 else 1)]


_TRAILING_FILLERS = {"um", "umm", "uh", "hmm", "mm", "mmm", "mhm", "mhmm"}
_SIGN_OFFS = {"okay", "ok", "yeah", "yep", "alright"}
_NOT_WORD_EDGE = re.compile(r"^[^\w']+|[^\w']+$")
_END_PUNCT = re.compile(r"[,;:!]+$")
_END_PERIOD = re.compile(r"(?<!\.)\.$")


def _without_one_sign_off(text: str) -> str | None:
    spans = [match.span() for match in re.finditer(r"\S+", text)]
    words = [text[start:end] for start, end in spans]
    cores = [_NOT_WORD_EDGE.sub("", word).lower() for word in words]
    if len(words) < 2:
        return None
    if cores[-1] in _TRAILING_FILLERS:
        kept = text[:spans[-2][1]].rstrip(",;:")
        ending = words[-1][-1] if words[-1][-1] in ".?!" else ""
        return kept if kept[-1:] in (".", "?", "!") else kept + ending
    glued = _NOT_WORD_EDGE.sub("", words[-1])
    if glued[:1].isupper() and cores[-1] in _SIGN_OFFS and words[-1][-1] not in "?!":
        return text[:spans[-2][1]].rstrip(",;:")
    for size in (2, 1):
        if (len(words) > size and all(core in _SIGN_OFFS for core in cores[-size:])
                and words[-size - 1][-1] in ".?!" and words[-1][-1] not in "?!"):
            return text[:spans[-size - 1][1]]
    return None


def without_sign_off(text: str) -> str:
    """The text without the "Okay." or "Um" trailed off at the end of the take. A sign-off is a last
    sentence of only those words, or a capitalized one glued on, and a lone one is the whole take."""
    while (shorter := _without_one_sign_off(text)) is not None:
        text = shorter
    return text


# Characters that render as nothing or that a reader cannot tell apart: format characters (zero-width,
# bidi controls, the tag block that spells hidden ASCII), controls other than the line break and tab,
# line and paragraph separators, private-use code points, and variation selectors, which can carry
# hidden bytes after a visible character.
_HIDDEN_CATEGORIES = {"Cf", "Cc", "Zl", "Zp", "Co"}
_VARIATION_SELECTORS = ((0xFE00, 0xFE0F), (0xE0100, 0xE01EF))
_SPECIAL_TOKEN_TEXT = re.compile(r"<\||\|>|</?think>")
_LINK = re.compile(r"://|@|www\.|\w\.[^\W\d_]{2,}")
_LINK_SPOKEN_SEPARATORS = {"dot", "at", "slash", "colon", "dash", "hyphen", "underscore", "period", "point"}


def _hidden(c: str) -> bool:
    if c in "\n\t":
        return False
    return unicodedata.category(c) in _HIDDEN_CATEGORIES or any(lo <= ord(c) <= hi for lo, hi in _VARIATION_SELECTORS)


def strip_hidden(text: str) -> str:
    """The text without characters a reader cannot see, which could carry instructions to a model
    that later reads the text, or make a link look like another."""
    return "".join(c for c in text if not _hidden(c))


def _letters_and_digits(text: str) -> list[str]:
    return re.findall(r"[^\W_]+", text.lower())


def unheard_link(raw: str, cleaned: str) -> str | None:
    """The first link, address or domain in the cleaned text whose letters and digits the speaker did
    not say in that order, spoken separators ("dot", "at", "slash") aside. A cleaner may write
    "example dot com" as example.com, but a link it adds can carry the dictation to a server that a
    messenger's link preview contacts while the message is still being written."""
    words = _letters_and_digits(raw)
    heard = ("".join(words), "".join(w for w in words if w not in _LINK_SPOKEN_SEPARATORS))
    for word in cleaned.split():
        if _LINK.search(word) and not any("".join(_letters_and_digits(word)) in h for h in heard):
            return word
    return None


def untrusted_clean(raw: str, cleaned: str) -> str | None:
    """Why the cleaner's output for raw must not be inserted, or None. The cleaner is a model from
    the internet, so what it writes is checked against what was said: no hidden characters, no
    chat-template tokens, no link the speaker did not dictate, and no name or number swapped for a
    near one ("drift"). Each falls back to the chunk as heard."""
    if strip_hidden(cleaned) != cleaned:
        return "hidden"
    if _SPECIAL_TOKEN_TEXT.search(cleaned):
        return "special"
    if unheard_link(raw, cleaned):
        return "link"
    if fact_drift(raw, cleaned):
        return "drift"
    return None


def trim_message_end(text: str) -> str:
    """The message without the punctuation left on its last character. A question mark stays, and so
    does an ellipsis; a final period, comma, semicolon, colon or exclamation mark goes."""
    body = text.rstrip()
    while True:
        stripped = _END_PERIOD.sub("", _END_PUNCT.sub("", body)).rstrip()
        if stripped == body:
            return body
        body = stripped


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
        if self.backend == "ollama" and _SPECIAL_TOKEN_TEXT.search(chunk):
            # Ollama parses special tokens anywhere in a raw prompt, so such a chunk is not sent.
            return chunk.strip(), 0
        parts = prompt_parts(self.spec["system_prompt"], self.spec["control_line"], chunk)
        limit = max_new_tokens(self.count_tokens(chunk), self.spec["max_new_tokens_ratio"],
                               self.spec["max_new_tokens_pad"])
        generated = self._generate(parts, limit, cancelled)
        return None if generated is None else (generated[0].strip(), generated[1])

    def clean(self, raw: str, skip: bool = True, min_tokens: int | None = None) -> Cleaned:
        """skip=False cleans even a take the skip rule would insert as recognized; min_tokens
        replaces the configured chunk size, which experiments use to compare against a whole pass."""
        started = time.monotonic()
        if skip and skips_cleaner(raw, self.cfg):
            return Cleaned(casual(without_sign_off(spoken_punctuation(raw)), self.cfg), int((time.monotonic() - started) * 1000), 0, 0, 0)
        outputs = []
        in_tokens = out_tokens = 0
        chunks = live_chunks(raw, self.count_tokens, min_tokens or self.cfg["chunk_min_tokens"],
                             self.cfg["chunk_max_tokens"], final=True, cues=self.cfg["self_correction_cues"])
        for chunk in chunks:
            text, n_out = self.clean_chunk(chunk)
            outputs.append(text)
            in_tokens += self.count_tokens(chunk)
            out_tokens += n_out
        text = join_cleaned(chunks, outputs, self.cfg["self_correction_cues"])
        text = without_sign_off(text)
        return Cleaned(casual(text, self.cfg) if skip else text, int((time.monotonic() - started) * 1000),
                       len(chunks), in_tokens, out_tokens)

    def _generate(self, parts: tuple[str, str, str], max_tokens: int,
                  cancelled: Callable[[], bool] | None = None) -> tuple[str, int] | None:
        # Both backends decode greedily with repeat_penalty 1.0: the bindings default to 1.1, and
        # the model cards specify plain greedy decoding. Only a cancellable call streams.
        if self.backend == "ollama":
            body = {
                "model": self.ollama_model, "prompt": "".join(parts), "raw": True, "stream": False,
                "keep_alive": OLLAMA_KEEP_ALIVE,
                "options": {"temperature": 0, "repeat_penalty": 1.0, "num_predict": max_tokens,
                            "num_ctx": 4096, "stop": [END_OF_TURN]},
            }
            if cancelled is not None:
                return _ollama_stream(body, cancelled)
            reply = _ollama(body)
            return reply["response"], reply.get("eval_count", 0)
        head, chunk, tail = parts
        tokens = (self._llm.tokenize(head.encode(), add_bos=False, special=True)
                  + self._llm.tokenize(chunk.encode(), add_bos=False, special=False)
                  + self._llm.tokenize(tail.encode(), add_bos=False, special=True))
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
