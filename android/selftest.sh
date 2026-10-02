#!/usr/bin/env bash
# Runs the debug SelfTest service on the test phone and prints its log line and result file path.
#
#     android/selftest.sh clean r05      # cleans files/selftest/r05.prompt.txt
#     android/selftest.sh wav r05        # recognizes files/selftest/r05.wav
#
# THREADS=n sets the recognizer thread count for `asr`.
# The result is pulled to $OUT (default /tmp/selftest). Stage inputs with push_selftest.py first.
set -euo pipefail

kind="$1"
name="$2"
OUT="${OUT:-/tmp/selftest}"
source ~/android-tooling/env.sh
S="$("$(dirname "$0")/test_device.sh")"
suffix=out.txt
case "$kind" in wav) suffix=take.txt ;; asr) suffix=asr.txt ;; esac

adb -s "$S" shell "run-as com.ezhou.sttbutton rm -f files/selftest/$name.$suffix"
adb -s "$S" logcat -c
adb -s "$S" shell am start-foreground-service -n com.ezhou.sttbutton/.SelfTest --es "$kind" "$name" ${THREADS:+--ei threads "$THREADS"} >/dev/null
for _ in $(seq "${WAIT_LOOPS:-150}"); do
    adb -s "$S" logcat -d -s SttButton:* | grep -E "selftest (wav|asr|clean) $name|selftest failed" && break
    sleep 2
done
mkdir -p "$OUT"
adb -s "$S" exec-out run-as com.ezhou.sttbutton cat "files/selftest/$name.$suffix" > "$OUT/$name.$suffix"
echo "$OUT/$name.$suffix"
