"""Measure what a Gboard-style live raw tail costs, and how much its words churn.

    pyrun python -m experiments.live_asr [--u 0.5,1.0]

Each recording plays on a simulated clock. Every U seconds the audio since the last commit is
re-decoded. Commits are the slices from a Slicer with slice_max_s 10, final slice included, each
recognized once. One JSON line per recording and U is appended to data/experiments/live/asr.jsonl.
"""

import argparse
import statistics
import sys
import time
from dataclasses import dataclass

import numpy as np

from experiments import paths, wer
from stt_button import config
from stt_button.asr import SAMPLE_RATE, Slicer, join_slices, read_wav

LIVE_SLICE_MAX_S = 10
MIN_TAIL_S = 0.3
MIN_TAIL_SAMPLES = int(MIN_TAIL_S * SAMPLE_RATE)


@dataclass(frozen=True)
class Decode:
    kind: str
    start: int
    end: int


@dataclass(frozen=True)
class Tick:
    end: int
    commit_end: int
    tail: tuple[int, int] | None


@dataclass
class Replay:
    commits: list[tuple[int, int]]
    calls: list[Decode]
    ticks: list[Tick]
    committed_text: str
    displayed: list[str]


def _words(text: str) -> list[str]:
    return text.split()


def retracted_words(displayed: list[str]) -> int:
    """Words that dropped out of the common prefix between consecutive displays."""
    retracted = 0
    for prev, nxt in zip(displayed, displayed[1:]):
        prev_words = _words(prev)
        kept = 0
        for a, b in zip(prev_words, _words(nxt)):
            if a != b:
                break
            kept += 1
        retracted += len(prev_words) - kept
    return retracted


def churn_per_100(displayed: list[str], final: str) -> float | None:
    """Retracted words per 100 words of the final text."""
    n = len(_words(final))
    if n == 0:
        return None
    return 100 * retracted_words(displayed) / n


def simulate(samples: np.ndarray, u_s: float, search_s: float, recognize,
             slice_max_s: float = LIVE_SLICE_MAX_S) -> Replay:
    """Step `now` by u_s and re-decode the uncommitted tail. recognize(samples) -> str.

    The last step lands on the end of the audio and may be shorter than u_s. The last displayed
    string is the committed text after finish(), so a tail word can still be retracted at stop.
    """
    step = int(round(u_s * SAMPLE_RATE))
    if step < 1:
        raise ValueError(f"U={u_s}s is shorter than one sample")
    slicer = Slicer(slice_max_s, search_s)
    texts: list[str] = []
    commits: list[tuple[int, int]] = []
    calls: list[Decode] = []
    ticks: list[Tick] = []
    displayed: list[str] = []
    commit_end = 0
    pos = 0
    n = len(samples)

    def commit(bounds: list[tuple[int, int]]) -> None:
        nonlocal commit_end
        for start, end in bounds:
            texts.append(recognize(samples[start:end]))
            commits.append((start, end))
            calls.append(Decode("commit", start, end))
            commit_end = end

    while pos < n:
        nxt = min(n, pos + step)
        commit(slicer.feed(samples[pos:nxt]))
        if nxt - commit_end >= MIN_TAIL_SAMPLES:
            tail_text = recognize(samples[commit_end:nxt])
            calls.append(Decode("tail", commit_end, nxt))
            tail = (commit_end, nxt)
        else:
            tail_text = ""
            tail = None
        ticks.append(Tick(nxt, commit_end, tail))
        displayed.append(join_slices([join_slices(texts), tail_text]))
        pos = nxt

    commit(slicer.finish())
    committed = join_slices(texts)
    displayed.append(committed)
    return Replay(commits, calls, ticks, committed, displayed)


def metrics(replay: Replay, audio_s: float, decode_s: float, baseline: str) -> dict:
    """decode_s is the wall time of every recognize call in the replay."""
    reference = wer.normalize(baseline)
    hypothesis = wer.normalize(replay.committed_text)
    errors = wer.word_errors(reference, hypothesis)
    ref_words = len(reference)
    return {
        "audio_s": audio_s,
        "decode_s": decode_s,
        "decode_per_audio_s": decode_s / audio_s if audio_s else None,
        "churn_per_100": churn_per_100(replay.displayed, replay.committed_text),
        "retracted_words": retracted_words(replay.displayed),
        "final_words": len(_words(replay.committed_text)),
        "wer": 100 * errors / ref_words if ref_words else None,
        "errors": errors,
        "ref_words": ref_words,
    }


def _median(values: list[float]) -> float | None:
    return statistics.median(values) if values else None


def summarize(rows: list[dict]) -> list[dict]:
    """Per U: medians of the three rates, and the same rates pooled over the recordings."""
    groups = []
    for u in sorted({round(float(r["u_s"]), 6) for r in rows}):
        g = [r for r in rows if round(float(r["u_s"]), 6) == u]
        audio = sum(r["audio_s"] for r in g)
        decode = sum(r["decode_s"] for r in g)
        retracted = sum(r["retracted_words"] for r in g)
        final_words = sum(r["final_words"] for r in g)
        errors = sum(r["errors"] for r in g)
        ref_words = sum(r["ref_words"] for r in g)
        groups.append({
            "u_s": u,
            "n": len(g),
            "median_decode_per_audio_s": _median(
                [r["decode_per_audio_s"] for r in g if r["decode_per_audio_s"] is not None]),
            "median_churn_per_100": _median(
                [r["churn_per_100"] for r in g if r["churn_per_100"] is not None]),
            "median_wer": _median([r["wer"] for r in g if r["wer"] is not None]),
            "decode_s": decode,
            "audio_s": audio,
            "total_decode_per_audio_s": decode / audio if audio else None,
            "retracted_words": retracted,
            "final_words": final_words,
            "total_churn_per_100": 100 * retracted / final_words if final_words else None,
            "errors": errors,
            "ref_words": ref_words,
            "total_wer": 100 * errors / ref_words if ref_words else None,
        })
    return groups


def _fmt(value, spec: str) -> str:
    return "-" if value is None else format(value, spec)


def print_summary(rows: list[dict]) -> None:
    groups = summarize(rows)
    if not groups:
        print("no live-asr results")
        return
    for g in groups:
        print(f"U={g['u_s']:g}  n={g['n']}")
        print(f"  median  decode/audio {_fmt(g['median_decode_per_audio_s'], '.4f')}  "
              f"churn/100 {_fmt(g['median_churn_per_100'], '.2f')}  "
              f"WER {_fmt(g['median_wer'], '.2f')}")
        print(f"  total   decode/audio {_fmt(g['total_decode_per_audio_s'], '.4f')} "
              f"({g['decode_s']:.2f}s/{g['audio_s']:.2f}s)  "
              f"churn/100 {_fmt(g['total_churn_per_100'], '.2f')} "
              f"({g['retracted_words']}/{g['final_words']})  "
              f"WER {_fmt(g['total_wer'], '.2f')} ({g['errors']}/{g['ref_words']})")


class _Timed:
    def __init__(self, recognize):
        self._recognize = recognize
        self.seconds = 0.0

    def __call__(self, samples: np.ndarray) -> str:
        started = time.monotonic()
        try:
            return self._recognize(samples)
        finally:
            self.seconds += time.monotonic() - started


def _round_floats(row: dict) -> dict:
    return {key: round(value, 6) if isinstance(value, float) else value for key, value in row.items()}


def parse_u(text: str) -> list[float]:
    seen = []
    for part in text.split(","):
        part = part.strip()
        if not part:
            continue
        value = float(part)
        if not value > 0:
            raise SystemExit(f"U must be positive, got {part}")
        key = round(value, 6)
        if key not in seen:
            seen.append(key)
    if not seen:
        raise SystemExit("pass at least one U, e.g. --u 0.5,1.0")
    return seen


def _u_key(value) -> float:
    return round(float(value), 6)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--u", default="0.5,1.0", help="comma-separated tail intervals in seconds")
    args = parser.parse_args()
    us = parse_u(args.u)

    sidecars = paths.sidecars()
    if not sidecars:
        sys.exit(f"no recordings in {paths.RECORDINGS}")
    done = {(r["id"], _u_key(r["u_s"])) for r in paths.read_jsonl(paths.LIVE_ASR)}
    pending = [(meta, u) for meta in sidecars for u in us if (meta["id"], u) not in done]
    if pending:
        from stt_button.asr import Recognizer

        recognizer = Recognizer()
        search_s = config.load()["slice_search_s"]
        loaded: dict[str, tuple[np.ndarray, str]] = {}
        for meta, u in pending:
            if meta["id"] not in loaded:
                wav = paths.RECORDINGS / f"{meta['id']}.wav"
                loaded[meta["id"]] = (read_wav(wav), recognizer.transcribe(wav).text)
            samples, baseline = loaded[meta["id"]]
            timed = _Timed(recognizer.recognize)
            replay = simulate(samples, u, search_s, timed)
            row = _round_floats({
                "id": meta["id"],
                "u_s": u,
                "mode": meta["mode"],
                **metrics(replay, len(samples) / SAMPLE_RATE, timed.seconds, baseline),
            })
            paths.append_jsonl(paths.LIVE_ASR, row)
            done.add((meta["id"], u))
            print(f"{meta['id']} U={u:g}  decode/audio {_fmt(row['decode_per_audio_s'], '.4f')}  "
                  f"churn/100 {_fmt(row['churn_per_100'], '.2f')}  WER {_fmt(row['wer'], '.2f')}",
                  flush=True)
    print_summary(paths.read_jsonl(paths.LIVE_ASR))
    return 0


if __name__ == "__main__":
    sys.exit(main())
