"""The streaming recognizer behind the live bubble, run on its own thread beside the take's worker."""

import sys
import threading
import time
from collections.abc import Callable

import numpy as np

from stt_button import config
from stt_button.asr import SAMPLE_RATE
from stt_button.bubble import words_from_tokens

THREADS = 2
IDLE_S = 0.05


class Preview(threading.Thread):
    """read() returns the audio appended since the last call; the thread ends once stopped()."""

    def __init__(self, bubble, read: Callable[[], np.ndarray], stopped: Callable[[], bool]):
        super().__init__(daemon=True)
        self.bubble = bubble
        self.read = read
        self.stopped = stopped

    def run(self) -> None:
        import sherpa_onnx

        model = config.model_dir() / config.load()["preview_model"]
        if not model.is_dir():
            print(f"preview model missing: {model}; no live bubble", file=sys.stderr)
            return
        recognizer = sherpa_onnx.OnlineRecognizer.from_transducer(
            tokens=str(model / "tokens.txt"), encoder=str(model / "encoder.int8.onnx"),
            decoder=str(model / "decoder.int8.onnx"), joiner=str(model / "joiner.int8.onnx"),
            num_threads=THREADS, decoding_method="greedy_search", model_type="nemo_transducer")
        stream = recognizer.create_stream()
        while not self.stopped():
            audio = self.read()
            if not len(audio):
                time.sleep(IDLE_S)
                continue
            stream.accept_waveform(SAMPLE_RATE, audio)
            while recognizer.is_ready(stream):
                recognizer.decode_stream(stream)
            result = recognizer.get_result_all(stream)
            self.bubble.preview(words_from_tokens(result.tokens, result.timestamps))
