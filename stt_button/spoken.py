"""Spoken punctuation: "quote … unquote", "open paren … close paren", "question mark", "new line".

Applied to raw text before the cleaner sees it, and on the path that skips the cleaner. A text with
no command comes back untouched; the Kotlin port in Spoken.kt is tested on the same vectors.
"""

import re

PAIR_WINDOW_WORDS = 30
_PAREN_WORDS = {"paren", "parenthesis", "parentheses"}
_TOKEN = re.compile(r"^([^\w']*)(.*?)([^\w']*)$", re.S)

_TWO_WORD = {
    ("open", "quote"): ("oq", None),
    ("close", "quote"): ("cq", None),
    ("end", "quote"): ("cq", None),
    ("quote", "unquote"): ("quq", None),
    ("question", "mark"): ("mark", "?"),
    ("exclamation", "point"): ("mark", "!"),
    ("exclamation", "mark"): ("mark", "!"),
    ("new", "line"): ("nl", "\n"),
    ("new", "paragraph"): ("nl", "\n\n"),
    **{("open", p): ("op", None) for p in _PAREN_WORDS},
    **{("close", p): ("cp", None) for p in _PAREN_WORDS},
}
_ONE_WORD = {"quote": "bq", "unquote": "cq", **{p: "bp" for p in _PAREN_WORDS}}
# "quote unquote or …" names the command rather than quoting the next word.
_CONJUNCTIONS = {"or", "and", "but", "nor"}
_CAPITALIZE_AFTER = {"?", "!", "\n\n"}
_COMMAND_KINDS = {"oq", "cq", "bq", "quq", "op", "cp", "bp", "mark", "nl"}


def _parse(text: str) -> list[tuple]:
    """Elements: ("w", text, lead+core, trail), or (kind, payload, words, trail) for a command,
    where words is the command as spoken, kept for a command that finds no partner."""
    tokens = [(t, *_TOKEN.match(t).groups()) for t in text.split()]
    cores = [core.lower() for _, _, core, _ in tokens]
    elements, i = [], 0
    while i < len(tokens):
        word, lead, core, trail = tokens[i]
        pair = (cores[i], cores[i + 1]) if i + 1 < len(tokens) and not trail else None
        if pair in _TWO_WORD:
            kind, payload = _TWO_WORD[pair]
            elements.append((kind, payload, f"{word} {tokens[i + 1][0]}", tokens[i + 1][3]))
            i += 2
        elif cores[i] in _ONE_WORD:
            elements.append((_ONE_WORD[cores[i]], None, word, trail))
            i += 1
        else:
            elements.append(("w", word, lead + core, trail))
            i += 1
    return elements


def _pair_up(elements: list[tuple]) -> list[tuple]:
    """Resolves commands into ("word", text), ("open", symbol), ("close", symbol, trail),
    ("mark", symbol), ("newline", symbol) and ("literal", text)."""
    paired: set[int] = set()

    def closer_after(i: int, kind: str) -> int | None:
        words = 0
        for j in range(i + 1, len(elements)):
            if elements[j][0] == "w":
                words += 1
                if words > PAIR_WINDOW_WORDS:
                    return None
            elif elements[j][0] == kind and j not in paired:
                return j
        return None

    out, i = [], 0
    while i < len(elements):
        kind, payload, spoken, trail = elements[i]
        if kind == "w":
            out.append(("word", payload))
        elif kind in ("oq", "bq"):
            j = closer_after(i, "cq")
            if j is None:
                out.append(("word", spoken))
            else:
                paired.add(j)
                out.append(("open", '"'))
        elif kind in ("op", "bp"):
            j = closer_after(i, "cp")
            if j is None:
                out.append(("word", spoken))
            else:
                paired.add(j)
                out.append(("open", "("))
        elif kind == "cq":
            out.append(("close", '"', trail) if i in paired else ("word", spoken))
        elif kind == "cp":
            out.append(("close", ")", trail) if i in paired else ("word", spoken))
        elif kind == "quq":
            following = elements[i + 1] if i + 1 < len(elements) else None
            if following is not None and following[0] == "w" and following[2].lower() not in _CONJUNCTIONS:
                out += [("open", '"'), ("word", following[2]), ("close", '"', following[3])]
                i += 1
            else:
                out.append(("word", spoken))
        elif kind == "mark":
            out.append(("mark", payload))
        else:
            out.append(("newline", payload))
        i += 1
    return out


def _render(items: list[tuple]) -> str:
    out: list[str] = []
    glue = capitalize = False

    def strip_end(chars: str) -> None:
        if out:
            out[-1] = out[-1].rstrip(chars)

    for kind, *rest in items:
        if kind == "word":
            text = rest[0]
            if capitalize and text[:1].islower():
                text = text[0].upper() + text[1:]
            if out and not glue:
                out.append(" ")
            out.append(text)
            glue = capitalize = False
        elif kind == "open":
            if out and not glue:
                out.append(" ")
            out.append(rest[0])
            glue = True
        elif kind == "close":
            strip_end(",")
            out.append(rest[0] + rest[1])
            glue = capitalize = False
        elif kind == "mark":
            strip_end(",.;:")
            out.append(rest[0])
            glue, capitalize = False, rest[0] in _CAPITALIZE_AFTER
        else:
            strip_end(",;:")
            out.append(rest[0])
            glue, capitalize = True, rest[0] in _CAPITALIZE_AFTER
    return "".join(out)


def keep_spoken_ending(chunk: str, cleaned: str) -> str:
    """A question or exclamation mark the speaker dictated at the end of a chunk outranks the full
    stop the cleaner prefers there ("ask if anyone wants it sooner question mark")."""
    spoken = spoken_punctuation(chunk).rstrip()
    if spoken != chunk.rstrip() and spoken[-1:] in ("?", "!") and cleaned.rstrip().endswith("."):
        return cleaned.rstrip()[:-1] + spoken[-1]
    return cleaned


def spoken_punctuation(text: str) -> str:
    elements = _parse(text)
    if not any(kind in _COMMAND_KINDS for kind, *_ in elements):
        return text
    items = _pair_up(elements)
    return text if all(kind == "word" for kind, *_ in items) else _render(items)
