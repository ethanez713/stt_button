"""Scores a candidate pipeline against what the user left in the field (plan 19).

    pyrun python -m experiments.correction_replay --logged
    pyrun python -m experiments.correction_replay --replay phone_replay.jsonl --name beam

The corpus is the takes `experiments.corrections` classes `untouched`, `marks` or `words` whose
recording is in data/phone/takes; a take's reference is the text the user left where it was inserted.
A candidate maps a take's audio, the field's text before it and the corrections of earlier takes to
text. `--logged` scores the text the phone inserted; `--replay` scores the output of
`android/replay_takes.sh`. Host candidates are Python functions given to `score`.
"""

import argparse
import json
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

import numpy as np

from experiments import config_ids, corrections, paths, wer
from experiments.phone_replay import PHONE, read_pcm, word_diff
from stt_button.asr import SAMPLE_RATE

CACHE = paths.EXPERIMENTS / "corrections"
CORPUS_CLASSES = ("untouched", "marks", "words")


@dataclass(frozen=True)
class Take:
    id: str
    ts: str
    pcm: Path
    text_before: str
    cleaned: str
    reference: str
    audio_s: float


@dataclass(frozen=True)
class Correction:
    """What the user changed in an earlier take: the cleaned words and the words they left."""
    before: str
    after: str


@dataclass(frozen=True)
class Candidate:
    """`run` returns the text and the seconds it cost; a change in behaviour needs a new `name` or `model`, which key the cache."""
    name: str
    model: str
    run: Callable[[Take, list[Correction]], tuple[str, float]]

    @staticmethod
    def of(name: str, model: str, fn: Callable[[np.ndarray, str, list[Correction]], str]) -> "Candidate":
        def run(take: Take, earlier: list[Correction]) -> tuple[str, float]:
            started = time.perf_counter()
            text = fn(read_pcm(take.pcm), take.text_before, earlier)
            return text, time.perf_counter() - started
        return Candidate(name, model, run)


@dataclass
class Scored:
    take: Take
    text: str
    seconds: float
    exact: bool
    by_words: bool
    errors: int
    reference_words: int
    baseline_right: bool


@dataclass
class Result:
    candidate: str
    rows: list[Scored] = field(default_factory=list)

    @property
    def fixed(self) -> list[Scored]:
        return [r for r in self.rows if r.by_words and not r.baseline_right]

    @property
    def broke(self) -> list[Scored]:
        return [r for r in self.rows if not r.by_words and r.baseline_right]


def corpus(lines: list[dict], takes: Path) -> list[Take]:
    """Takes with a reference and a recording, oldest first."""
    counted, _ = corrections.analyse(lines)
    out = []
    for r in counted:
        pcm = takes / f"{r['id']}.pcm"
        if r["class"] in CORPUS_CLASSES and pcm.exists():
            out.append(Take(r["id"], r["ts"] or "", pcm, r["text_before"], r["cleaned"], r["reference"],
                            pcm.stat().st_size / 2 / SAMPLE_RATE))
    out.sort(key=lambda t: (t.ts, t.id))
    return out


def earlier_corrections(lines: list[dict], before_ts: str, before_id: str) -> list[Correction]:
    """The word runs of every `words` take that came before this one, and no later take's."""
    counted, _ = corrections.analyse(lines)
    out = []
    for r in sorted(counted, key=lambda r: (r["ts"] or "", r["id"])):
        if (r["ts"] or "", r["id"]) >= (before_ts, before_id):
            break
        out += [Correction(" ".join(b), " ".join(a)) for b, a in r["runs"]]
    return out


def _cached(candidate: Candidate, take: Take, earlier: list[Correction], cache: Path) -> tuple[str, float]:
    path = cache / f"{candidate.name}__{candidate.model}" / f"{take.id}.json"
    if path.exists():
        saved = json.loads(path.read_text())
        return saved["text"], saved["seconds"]
    text, seconds = candidate.run(take, earlier)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps({"text": text, "seconds": seconds}, ensure_ascii=False))
    return text, seconds


def score(lines: list[dict], takes: Path, candidate: Candidate, cache: Path = CACHE) -> Result:
    result = Result(f"{candidate.name} [{candidate.model}]")
    for take in corpus(lines, takes):
        earlier = earlier_corrections(lines, take.ts, take.id)
        text, seconds = _cached(candidate, take, earlier, cache)
        reference, heard = wer.normalize(take.reference), wer.normalize(text)
        result.rows.append(Scored(
            take, text, seconds, text.strip() == take.reference.strip(), heard == reference,
            wer.word_errors(reference, heard), len(reference), wer.normalize(take.cleaned) == reference))
    return result


def report(result: Result) -> str:
    rows = result.rows
    if not rows:
        return f"{result.candidate}: no take in the corpus"
    errors, words = sum(r.errors for r in rows), sum(r.reference_words for r in rows)
    audio = sum(r.take.audio_s for r in rows)
    out = [f"{result.candidate}: {len(rows)} takes scored",
           f"  zero-edit rate: exact {sum(r.exact for r in rows) / len(rows) * 100:.0f}%, "
           f"by words {sum(r.by_words for r in rows) / len(rows) * 100:.0f}%",
           f"  word error rate against the reference: {errors / max(words, 1) * 100:.1f}% ({errors} of {words} words)",
           f"  host compute: {sum(r.seconds for r in rows) / max(audio, 1e-9):.3f} s per audio second",
           f"  fixed {len(result.fixed)}, broke {len(result.broke)} against the logged text"]
    for label, group in (("fixed", result.fixed), ("broke", result.broke)):
        for r in group:
            for logged, now in word_diff(r.take.cleaned, r.text):
                out.append(f"    {label} {r.take.id[:8]}: {logged or '∅'} → {now or '∅'}")
    return "\n".join(out)


def logged_candidate(lines: list[dict]) -> Candidate:
    """The text the phone inserted, as it was logged."""
    cleaned = {line["id"]: line["cleaned"] for line in lines if line.get("event") == "insert"}
    return Candidate("logged", "phone", lambda take, earlier: (cleaned[take.id], 0.0))


def replay_candidate(path: Path, name: str) -> Candidate:
    """The output of `android/replay_takes.sh`, one line per recording; its cost is the phone's recognizer and cleaner time."""
    by_id = {Path(row["name"]).stem: row for row in paths.read_jsonl(path)}
    config_id = next(iter({row["config_id"] for row in by_id.values()}), "unknown")

    def run(take: Take, earlier: list[Correction]) -> tuple[str, float]:
        row = by_id[take.id]
        return row["cleaned"], (row["asr_ms"] + row["clean_ms"]) / 1000

    return Candidate(name, config_id, run)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dir", type=Path, default=PHONE, help="holds edits.jsonl and takes/")
    parser.add_argument("--logged", action="store_true", help="score the text the phone inserted")
    parser.add_argument("--replay", type=Path, help="score a replay_takes.sh output")
    parser.add_argument("--name", default="replay")
    parser.add_argument("--all-configs", action="store_true", help="pool takes whose configurations differ")
    args = parser.parse_args(argv)
    lines = paths.read_jsonl(args.dir / "edits.jsonl")
    header, ok = config_ids.check(lines, args.all_configs)
    print(header)
    if not ok:
        return 1
    if not (args.logged or args.replay):
        parser.error("name a candidate: --logged or --replay")
    if args.logged:
        print(report(score(lines, args.dir / "takes", logged_candidate(lines))))
    if args.replay:
        print(report(score(lines, args.dir / "takes", replay_candidate(args.replay, args.name))))
    return 0


if __name__ == "__main__":
    sys.exit(main())
