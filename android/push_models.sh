#!/usr/bin/env bash
# Copies the model files from $STT_MODEL_DIR into the app's files dir on the phone. run-as cannot
# read shared storage, so each file goes through /data/local/tmp, which is emptied after the copy.
# Every file must hash to its pin in config/models.json, on the host before the push and on the
# phone after it; a file the phone already holds with that hash is skipped.
set -euo pipefail

source ~/android-tooling/env.sh
MODELS="${STT_MODEL_DIR:-$HOME/models/stt_button}"
PKG=com.ezhou.sttbutton
STAGE=/data/local/tmp/stt_stage
SERIAL="$("$(dirname "$0")/test_device.sh")"
FETCH="$(dirname "$0")/../scripts/fetch_models.py"

FILES=(
    unified/encoder.int8.onnx unified/decoder.int8.onnx unified/joiner.int8.onnx unified/tokens.txt
    s1-mini/s1-mini-q4_k_m.gguf
    fc-stream480/encoder.int8.onnx fc-stream480/decoder.int8.onnx fc-stream480/joiner.int8.onnx fc-stream480/tokens.txt
)

device_sha256() {
    adb -s "$SERIAL" shell "run-as $PKG sha256sum $1 2>/dev/null" | tr -d '\r' | cut -d' ' -f1 || true
}

for rel in "${FILES[@]}"; do
    dest="files/models/$rel"
    pinned="$(python3 -I "$FETCH" --expected "$rel")"
    local_sha="$(sha256sum "$MODELS/$rel" | cut -d' ' -f1)"
    if [ "$local_sha" != "$pinned" ]; then
        echo "sha256 mismatch for $MODELS/$rel: pinned $pinned, got $local_sha" >&2
        exit 1
    fi
    if [ "$(device_sha256 "$dest")" = "$pinned" ]; then
        echo "have $rel"
        continue
    fi
    echo "push $rel"
    adb -s "$SERIAL" push "$MODELS/$rel" "$STAGE" >/dev/null
    adb -s "$SERIAL" shell "run-as $PKG mkdir -p $(dirname "$dest") && run-as $PKG cp $STAGE $dest; rm -f $STAGE"
    on_phone="$(device_sha256 "$dest")"
    if [ "$on_phone" != "$pinned" ]; then
        echo "sha256 mismatch for $dest on the phone: pinned $pinned, got ${on_phone:-nothing}" >&2
        exit 1
    fi
done
