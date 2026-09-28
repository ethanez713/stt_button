import argparse
import dataclasses
import json
import sys

from stt_button import run


def main() -> int:
    parser = argparse.ArgumentParser(prog="python -m stt_button", description="Transcribe and clean one wav.")
    parser.add_argument("wav")
    parser.add_argument("--recognizer", help="override config/pipeline.json's recognizer")
    parser.add_argument("--cleaner", help="override config/pipeline.json's cleaner")
    args = parser.parse_args()
    result = run(args.wav, args.recognizer, args.cleaner)
    print(json.dumps(dataclasses.asdict(result), ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
