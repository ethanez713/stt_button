import json
from pathlib import Path

from stt_button.config import REPO

RECORDINGS = REPO / "data" / "recordings"
EXPERIMENTS = REPO / "data" / "experiments"
ASR = EXPERIMENTS / "asr"
ASR_RESULTS = ASR / "results.jsonl"
CLEAN = EXPERIMENTS / "clean"
CLEAN_RESULTS = CLEAN / "results.jsonl"
BLIND_KEY = CLEAN / "blind_key.json"
VOTES = CLEAN / "votes.jsonl"
LIVE = EXPERIMENTS / "live"
LIVE_ASR = LIVE / "asr.jsonl"


def sidecars(recordings: Path = RECORDINGS) -> list[dict]:
    return [json.loads(p.read_text()) for p in sorted(recordings.glob("*.json"))]


def read_jsonl(path: Path) -> list[dict]:
    if not path.exists():
        return []
    return [json.loads(line) for line in path.read_text().splitlines() if line.strip()]


def append_jsonl(path: Path, record: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a") as f:
        f.write(json.dumps(record, ensure_ascii=False) + "\n")
