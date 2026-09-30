"""Clean transcripts in growing sentence chunks and compare each size with one whole pass.

    STT_MODEL_DIR=~/models/stt_button pyrun python -m experiments.chunk_clean [--min-words 0] [--k 64 128 256]

Reads the recognizer's transcripts, the same directory as experiments.backend_parity. The whole-pass
reference is data/experiments/clean/s1-mini/<id>.txt, generated with this Cleaner when it is missing.
Chunked output is data/experiments/live/chunk<K>/<id>.txt.
"""

import argparse
import gc
import sys
from collections.abc import Callable

from experiments import paths
from experiments.backend_parity import word_changes
from stt_button import config
from stt_button.cleaner import Cleaner, split_sentences

WHOLE_ARM = "s1-mini"


def cut_chunks(text: str, count_tokens: Callable[[str], int], k: int) -> list[str]:
    """Close a chunk once it holds at least k tokens of whole sentences.

    A sentence longer than k stays whole; live cleaning cuts only at sentence boundaries.
    The final chunk is the remainder, at any size.
    """
    chunks: list[str] = []
    current: list[str] = []
    for sentence in split_sentences(text):
        current.append(sentence)
        chunk = " ".join(current)
        if count_tokens(chunk) >= k:
            chunks.append(chunk)
            current = []
    if current:
        chunks.append(" ".join(current))
    return chunks


def whole_path(name: str):
    return paths.CLEAN / WHOLE_ARM / f"{name}.txt"


def chunk_path(k: int, name: str):
    return paths.LIVE / f"chunk{k}" / f"{name}.txt"


def load_transcripts(min_words: int) -> dict[str, str]:
    source = paths.ASR / config.load()["recognizer"]
    found = {}
    for path in sorted(source.glob("*.txt")):
        text = path.read_text().strip()
        if len(text.split()) >= min_words:
            found[path.stem] = text
    if not found:
        sys.exit(f"no transcripts of at least {min_words} words in {source}")
    return found


def write_text(path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text + "\n")


def generate(texts: dict[str, str], ks: list[int]) -> None:
    pending = []
    for name, text in texts.items():
        missing_whole = not whole_path(name).is_file()
        missing_k = [k for k in ks if not chunk_path(k, name).is_file()]
        if missing_whole or missing_k:
            pending.append((name, text, missing_whole, missing_k))
    if not pending:
        return

    cleaner = Cleaner()
    try:
        for name, text, missing_whole, missing_k in pending:
            if missing_whole:
                write_text(whole_path(name), cleaner.clean(text).text)
                print(f"  {name} whole", flush=True)
            for k in missing_k:
                chunks = cut_chunks(text, cleaner.count_tokens, k)
                cleaned = [cleaner.clean(chunk).text for chunk in chunks]
                write_text(chunk_path(k, name), " ".join(part for part in cleaned if part))
                print(f"  {name} chunk{k}: {len(chunks)} chunk(s)", flush=True)
    finally:
        cleaner.release()
        del cleaner
        gc.collect()


def report(names: list[str], ks: list[int]) -> None:
    for k in ks:
        rows = []
        for name in names:
            whole = whole_path(name).read_text().strip()
            chunked = chunk_path(k, name).read_text().strip()
            rows.append((name, whole, chunked))
        identical = sum(whole == chunked for _, whole, chunked in rows)
        print(f"chunk{k}: identical {identical} of {len(rows)} vs whole")
        for name, whole, chunked in rows:
            if whole != chunked:
                print(f"  {name}: {word_changes(whole, chunked)}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--min-words", type=int, default=0)
    parser.add_argument("--k", type=int, nargs="+", default=[64, 128, 256])
    args = parser.parse_args()

    texts = load_transcripts(args.min_words)
    generate(texts, args.k)
    report(list(texts), args.k)
    return 0


if __name__ == "__main__":
    sys.exit(main())
