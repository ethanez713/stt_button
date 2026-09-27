import json
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
PIPELINE_JSON = REPO / "config" / "pipeline.json"


def load() -> dict:
    return json.loads(PIPELINE_JSON.read_text())
