#!/usr/bin/env bash
# Pulls the test phone's edit log and the audio of the takes it has kept into data/phone/, for
# experiments.phone_replay. Audio already pulled stays after the phone has pruned it.
set -euo pipefail

source ~/android-tooling/env.sh
S="$("$(dirname "$0")/test_device.sh")"
PKG=com.ezhou.sttbutton
OUT="$(cd "$(dirname "$0")/.." && pwd)/data/phone"

mkdir -p "$OUT/takes"
adb -s "$S" exec-out run-as "$PKG" cat files/logs/edits.jsonl > "$OUT/edits.jsonl"
for name in $(adb -s "$S" shell run-as "$PKG" ls files/takes | tr -d '\r'); do
    adb -s "$S" exec-out run-as "$PKG" cat "files/takes/$name" > "$OUT/takes/$name"
done
echo "$OUT: $(wc -l < "$OUT/edits.jsonl") log lines, $(find "$OUT/takes" -name '*.pcm' | wc -l) takes"
