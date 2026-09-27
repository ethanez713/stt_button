import json
import math
import wave
from datetime import datetime, timezone

import pytest

from stt_button import record


def write_wav(path, samples, rate=16000, channels=1):
    with wave.open(str(path), "wb") as w:
        w.setnchannels(channels)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(b"".join(int(s).to_bytes(2, "little", signed=True) for s in samples))


def tone(seconds, amplitude=0.1, rate=16000):
    return [amplitude * 32767 * math.sin(2 * math.pi * 440 * i / rate) for i in range(int(seconds * rate))]


PROMPTS = {
    "read": [{"id": "r01", "text": "one"}, {"id": "r02", "text": "two"}],
    "ramble": [{"id": "m01", "text": "topic"}],
}


def test_sine_level_and_duration(tmp_path):
    wav = tmp_path / "a.wav"
    write_wav(wav, tone(1.5, amplitude=0.1))
    duration, dbfs = record.wav_stats(wav)
    assert duration == pytest.approx(1.5)
    # A sine's RMS is amplitude / sqrt(2): 0.1 -> about -23 dBFS.
    assert dbfs == pytest.approx(20 * math.log10(0.1 / math.sqrt(2)), abs=0.1)


def test_digital_silence_hits_floor_and_is_flagged_silent(tmp_path):
    wav = tmp_path / "a.wav"
    write_wav(wav, [0] * 16000)
    _, dbfs = record.wav_stats(wav)
    assert dbfs == record.DBFS_FLOOR
    assert dbfs < record.SILENT_DBFS


def test_empty_recording_does_not_divide_by_zero(tmp_path):
    wav = tmp_path / "a.wav"
    write_wav(wav, [])
    assert record.wav_stats(wav) == (0.0, record.DBFS_FLOOR)


@pytest.mark.parametrize("rate,channels", [(44100, 1), (16000, 2)])
def test_rejects_wrong_format(tmp_path, rate, channels):
    wav = tmp_path / "a.wav"
    write_wav(wav, [0] * 100, rate=rate, channels=channels)
    with pytest.raises(ValueError):
        record.wav_stats(wav)


def test_sidecar_carries_reference_only_for_read(tmp_path):
    ts = datetime(2026, 9, 27, 12, 0, tzinfo=timezone.utc)
    read_wav = tmp_path / "r01-x.wav"
    ramble_wav = tmp_path / "m01-x.wav"
    write_wav(read_wav, tone(0.5))
    write_wav(ramble_wav, tone(0.5))

    read_meta = record.write_sidecar(read_wav, PROMPTS["read"][0], "read", ts)
    ramble_meta = record.write_sidecar(ramble_wav, PROMPTS["ramble"][0], "ramble", ts)

    assert read_meta["reference"] == "one"
    assert "reference" not in ramble_meta
    assert json.loads(read_wav.with_suffix(".json").read_text()) == read_meta
    assert read_meta["id"] == "r01-x" and read_meta["prompt_id"] == "r01"


def test_next_prompt_skips_recorded_and_ends(tmp_path):
    ts = datetime.now(timezone.utc)
    assert record.next_prompt("read", PROMPTS, tmp_path)["id"] == "r01"

    wav = tmp_path / "r01-x.wav"
    write_wav(wav, tone(0.1))
    record.write_sidecar(wav, PROMPTS["read"][0], "read", ts)
    assert record.next_prompt("read", PROMPTS, tmp_path)["id"] == "r02"

    wav = tmp_path / "r02-x.wav"
    write_wav(wav, tone(0.1))
    record.write_sidecar(wav, PROMPTS["read"][1], "read", ts)
    assert record.next_prompt("read", PROMPTS, tmp_path) is None
    assert record.next_prompt("ramble", PROMPTS, tmp_path)["id"] == "m01"


def test_committed_prompts_file_has_the_planned_shape():
    prompts = record.load_prompts()
    assert len(prompts["read"]) == 12 and len(prompts["ramble"]) == 10
    ids = [p["id"] for mode in prompts.values() for p in mode]
    assert len(ids) == len(set(ids))
