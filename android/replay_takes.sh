#!/usr/bin/env bash
# Runs recordings (.pcm or .wav) through the phone's own take pipeline and pulls one JSON line each.
#
#     android/replay_takes.sh [--config pipeline.json] <out.jsonl> <files...>
#
# With --config the phone replays under that file in place of the bundled pipeline.json, and each
# line's config_id is computed from it; daily takes are untouched. Needs the debug build.
set -euo pipefail

config=""
if [ "${1:-}" = "--config" ]; then
    config="$2"
    shift 2
fi
[ "$#" -ge 2 ] || { sed -n '2,8p' "$0" >&2; exit 2; }
out="$1"
shift

source ~/android-tooling/env.sh
S="$("$(dirname "$0")/test_device.sh")"
PKG=com.ezhou.sttbutton
STAGE=/data/local/tmp/stt_replay
DIR=files/selftest/replay

adb -s "$S" shell "rm -rf $STAGE; mkdir -p $STAGE"
adb -s "$S" push "$@" "$STAGE/" >/dev/null
[ -z "$config" ] || adb -s "$S" push "$config" "$STAGE/pipeline.json" >/dev/null
adb -s "$S" shell "run-as $PKG sh -c 'rm -rf $DIR files/selftest/replay.jsonl; mkdir -p $DIR; cp $STAGE/* $DIR/'; rm -rf $STAGE"
adb -s "$S" logcat -c
adb -s "$S" shell am start-foreground-service -n "$PKG/.SelfTest" --ez replay true >/dev/null
result=""
for _ in $(seq "${WAIT_LOOPS:-900}"); do
    result="$(adb -s "$S" logcat -d -s SttButton:* | grep -E "selftest replay done|selftest failed" || true)"
    [ -n "$result" ] && break
    sleep 2
done
mkdir -p "$(dirname "$out")"
adb -s "$S" exec-out run-as "$PKG" cat files/selftest/replay.jsonl > "$out"
adb -s "$S" shell "run-as $PKG rm -rf $DIR"
case "$result" in *"replay done"*) ;; *) echo "replay did not finish: ${result:-timed out}" >&2; exit 1 ;; esac
echo "$out: $(wc -l < "$out") lines for $# files"
