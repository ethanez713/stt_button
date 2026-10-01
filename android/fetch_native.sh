#!/usr/bin/env bash
# Downloads sherpa-onnx v1.13.8's prebuilt arm64-v8a libraries into app/src/main/jniLibs, checking
# the pinned hash. The Kotlin API sources it ships are committed under app/src/main/java.
set -euo pipefail

cd "$(dirname "$0")"
CACHE="${STT_NATIVE_CACHE:-$HOME/.cache/stt_button}"
URL=https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-v1.13.8-android.tar.bz2
SHA256=2ff63469a71cb6009aa2e3ed5f4a670f8abdcbe4bb9ffd23776afc792a6b4f44
DEST=app/src/main/jniLibs/arm64-v8a

mkdir -p "$CACHE" "$DEST"
ARCHIVE="$CACHE/sherpa-onnx-v1.13.8-android.tar.bz2"
[ -f "$ARCHIVE" ] || curl -fL -o "$ARCHIVE" "$URL"
echo "$SHA256  $ARCHIVE" | sha256sum -c -
tar -xjf "$ARCHIVE" -C "$DEST" --strip-components=3 ./jniLibs/arm64-v8a/libonnxruntime.so ./jniLibs/arm64-v8a/libsherpa-onnx-jni.so
ls -la "$DEST"
