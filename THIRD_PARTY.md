# Third-party notices

The MIT license in `LICENSE` covers this project's own code. The code and weights below stay under their own licenses. Model files are not in git; `config/models.json` pins each URL and sha256, and `scripts/fetch_models.py` checks those hashes on download.

## Code in this repository

**sherpa-onnx Kotlin API.** `android/app/src/main/java/com/k2fsa/sherpa/onnx/`, Apache-2.0. The license text is `android/app/src/main/java/com/k2fsa/sherpa/onnx/LICENSE`. These are the Kotlin bindings from the sherpa-onnx v1.13.8 Android package (`android/fetch_native.sh`).

**llama.cpp.** Git submodule `android/third_party/llama.cpp` at `da89bb3ccc61edc4fc0d18671c927648e42af99c` ([ggml-org/llama.cpp](https://github.com/ggml-org/llama.cpp)), MIT, Copyright (c) 2023-2026 The ggml authors. The phone cleaner calls it through JNI (`android/app/src/main/cpp/cleaner_jni.cpp`).

**Prebuilt native libraries, not committed.** `android/fetch_native.sh` downloads the sherpa-onnx v1.13.8 Android archive and unpacks `libsherpa-onnx-jni.so` (Apache-2.0, sherpa-onnx) and `libonnxruntime.so` (MIT, ONNX Runtime) into `android/app/src/main/jniLibs/`, which git ignores.

## Models

`config/pipeline.json` selects `unified` for recognition, `fc-stream480` for the live preview, and `s1-mini` for cleanup. The other three entries are the host comparison set.

| Key | Weights | License |
| --- | --- | --- |
| `unified` | Parakeet Unified EN 0.6B by NVIDIA, int8 sherpa-onnx package | NVIDIA Open Model License. Use and redistribution keep the license and NVIDIA's attribution notice. |
| `fc-stream480` | sherpa-onnx conversion of NVIDIA `stt_en_fastconformer_hybrid_large_streaming_multi` | CC-BY-4.0. Credit NVIDIA. |
| `tdt-v2` | Parakeet TDT 0.6B v2 by NVIDIA | CC-BY-4.0. Credit NVIDIA. |
| `ultra` | Parakeet Ultra, Moondream's post-training of Parakeet TDT 0.6B v3, int8 ONNX from `mldecode/parakeet-ultra-onnx-int8` | CC-BY-4.0. Credit Moondream and NVIDIA. |
| `s1-mini` | S1-mini by Superwhisper, Q4_K_M GGUF | Apache-2.0. Wherever the weights are used they stay "S1-mini" by "Superwhisper", capitalization included, with the Apache `LICENSE` and `NOTICE` retained. |
| `qwen3.5-2b` | Qwen3.5-2B by Qwen, Q4_K_M GGUF from `unsloth/Qwen3.5-2B-GGUF` | Apache-2.0. |

The about screen in the app repeats the notice for the three models the phone runs.
