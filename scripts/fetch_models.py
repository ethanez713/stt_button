"""Download every model in config/models.json into $STT_MODEL_DIR and verify sha256.

Rerunning re-verifies what is already present and downloads only what is missing.
A null sha256 is filled in from the first download and must then be committed.
"""

import hashlib
import json
import os
import shutil
import subprocess
import sys
import tarfile
from pathlib import Path

MODELS_JSON = Path(__file__).resolve().parent.parent / "config" / "models.json"


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def fetch(url: str, dest: Path, expected: str | None) -> str:
    if not dest.exists():
        print(f"downloading {dest.name}", flush=True)
        dest.parent.mkdir(parents=True, exist_ok=True)
        partial = dest.with_suffix(dest.suffix + ".part")
        # curl, not urllib: urllib stalls on GitHub release redirects here, and curl can resume a .part.
        subprocess.run(["curl", "-fsSL", "--retry", "30", "--retry-all-errors", "--retry-delay", "5", "-C", "-", "-o", str(partial), url], check=True)
        partial.rename(dest)
    actual = sha256(dest)
    if expected and actual != expected:
        sys.exit(f"sha256 mismatch for {dest}: expected {expected}, got {actual}")
    print(f"ok {dest.name}", flush=True)
    return actual


def main() -> int:
    root = Path(os.environ.get("STT_MODEL_DIR") or sys.exit("set STT_MODEL_DIR"))
    manifest = json.loads(MODELS_JSON.read_text())
    filled = False
    for name, spec in manifest.items():
        if "archive" in spec:
            archive = root / "_archives" / spec["archive"]["url"].rsplit("/", 1)[1]
            fetch(spec["archive"]["url"], archive, spec["archive"]["sha256"])
            if not (root / name).exists():
                # Extract beside the target and rename last, so an interrupted run leaves no half model.
                staging = root / f".{name}.extracting"
                shutil.rmtree(staging, ignore_errors=True)
                with tarfile.open(archive) as tar:
                    top = tar.getnames()[0].split("/")[0]
                    tar.extractall(staging, filter="data")
                (staging / top).rename(root / name)
                staging.rmdir()
        for filename, f in spec.get("files", {}).items():
            actual = fetch(f["url"], root / name / filename, f["sha256"])
            if f["sha256"] is None:
                f["sha256"] = actual
                filled = True
    if filled:
        MODELS_JSON.write_text(json.dumps(manifest, indent=2) + "\n")
        print("filled missing sha256 values in config/models.json; commit them")
    return 0


if __name__ == "__main__":
    sys.exit(main())
