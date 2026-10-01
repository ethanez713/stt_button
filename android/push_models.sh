#!/usr/bin/env bash
# Copies the model files from $STT_MODEL_DIR into the app's files dir on the phone. run-as cannot
# read shared storage, so each file goes through /data/local/tmp, which is emptied after the copy.
# Files already present at the same size are skipped.
set -euo pipefail

source ~/android-tooling/env.sh
MODELS="${STT_MODEL_DIR:-$HOME/models/stt_button}"
PKG=com.ezhou.sttbutton
STAGE=/data/local/tmp/stt_stage
SERIAL="$("$(dirname "$0")/test_device.sh")"

FILES=(
    unified/encoder.int8.onnx unified/decoder.int8.onnx unified/joiner.int8.onnx unified/tokens.txt
    s1-mini/s1-mini-q4_k_m.gguf
    fc-stream480/encoder.int8.onnx fc-stream480/decoder.int8.onnx fc-stream480/joiner.int8.onnx fc-stream480/tokens.txt
)

for rel in "${FILES[@]}"; do
    dest="files/models/$rel"
    size="$(stat -c %s "$MODELS/$rel")"
    have="$(adb -s "$SERIAL" shell "run-as $PKG stat -c %s $dest 2>/dev/null" | tr -d '\r' || true)"
    if [ "$have" = "$size" ]; then
        echo "have $rel"
        continue
    fi
    echo "push $rel"
    adb -s "$SERIAL" push "$MODELS/$rel" "$STAGE" >/dev/null
    adb -s "$SERIAL" shell "run-as $PKG mkdir -p $(dirname "$dest") && run-as $PKG cp $STAGE $dest; rm -f $STAGE"
done
