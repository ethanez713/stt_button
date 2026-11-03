import json
import os
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
PIPELINE_JSON = REPO / "config" / "pipeline.json"
NAMES_JSON = REPO / "config" / "names.json"


def render_control_line(template: str | None, style: dict) -> str | None:
    if template is None:
        return None
    return template.replace("{styling}", style["styling"]).replace("{structure}", style["structure"])


def _name_map(path: Path) -> list[list[str]]:
    if not path.is_file():
        return []
    pairs = json.loads(path.read_text())
    return [[heard, written] for heard, written in pairs or []]


def load() -> dict:
    cfg = json.loads(PIPELINE_JSON.read_text())
    cfg["name_map"] = _name_map(NAMES_JSON)
    # The file stores a template. Callers read control_line as the prompt text, which is the default style.
    default = cfg["styles"]["default"]
    for spec in cfg["cleaners"].values():
        spec["control_line"] = render_control_line(spec["control_line"], default)
    return cfg


def model_dir() -> Path:
    value = os.environ.get("STT_MODEL_DIR")
    if not value:
        raise RuntimeError("STT_MODEL_DIR is not set; run scripts/fetch_models.py into it first")
    path = Path(value).expanduser()
    if not path.is_dir():
        raise FileNotFoundError(f"STT_MODEL_DIR {path} does not exist")
    return path
