import json
import os
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
PIPELINE_JSON = REPO / "config" / "pipeline.json"


def load() -> dict:
    return json.loads(PIPELINE_JSON.read_text())


def model_dir() -> Path:
    value = os.environ.get("STT_MODEL_DIR")
    if not value:
        raise RuntimeError("STT_MODEL_DIR is not set; run scripts/fetch_models.py into it first")
    path = Path(value).expanduser()
    if not path.is_dir():
        raise FileNotFoundError(f"STT_MODEL_DIR {path} does not exist")
    return path
