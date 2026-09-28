import os
from pathlib import Path

import pytest

from stt_button import config

RECORDINGS = config.REPO / "data" / "recordings"

pytestmark = pytest.mark.slow


def first_recording() -> Path:
    wavs = sorted(RECORDINGS.glob("*.wav"))
    if not wavs:
        pytest.skip("no recording in data/recordings")
    return wavs[0]


@pytest.mark.skipif(not os.environ.get("STT_MODEL_DIR"), reason="STT_MODEL_DIR unset")
def test_recording_goes_through_both_stages():
    from stt_button import run

    result = run(first_recording())

    assert result.raw.strip()
    assert result.cleaned.strip()
    assert result.asr_ms > 0 and result.clean_ms > 0
