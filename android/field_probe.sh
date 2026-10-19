#!/usr/bin/env bash
# Rewrites the focused field through the accessibility service's input connection and tabulates the result.
#
#     android/field_probe.sh read
#     android/field_probe.sh replace <from> <to> <text>
#     android/field_probe.sh typing | append       # ten writes, 2 s apart
#     GUARD=1 android/field_probe.sh typing          # each write held until the caret follows a non-word character
#
# The result is pulled to $OUT (default /tmp/selftest/probe.jsonl). Needs the debug build.
set -euo pipefail

mode="${1:?mode: read | replace | typing | append}"
OUT="${OUT:-/tmp/selftest/probe.jsonl}"
source ~/android-tooling/env.sh
S="$("$(dirname "$0")/test_device.sh")"
PKG=com.ezhou.sttbutton

extras=()
[ -n "${GUARD:-}" ] && extras+=(--ez guard true)
if [ "$mode" = replace ]; then
    extras+=(--ei from "${2:?from}" --ei to "${3:?to}" --es text "${4:-}")
fi
adb -s "$S" shell "run-as $PKG rm -f files/selftest/probe.jsonl"
adb -s "$S" logcat -c
adb -s "$S" shell am start-foreground-service -n "$PKG/.SelfTest" --es probe "$mode" "${extras[@]}" >/dev/null
for _ in $(seq "${WAIT_LOOPS:-60}"); do
    adb -s "$S" logcat -d -s SttButton:* | grep -qE "selftest probe done|selftest failed" && break
    sleep 2
done
mkdir -p "$(dirname "$OUT")"
adb -s "$S" exec-out run-as "$PKG" cat files/selftest/probe.jsonl > "$OUT"
python3 - "$OUT" <<'PY'
import json, sys
from collections import defaultdict
rows = [json.loads(line) for line in open(sys.argv[1]) if line.strip()]
groups = defaultdict(list)
for r in rows:
    groups[(r["pkg"], r["mode"], r["field_read"])].append(r)
print(f"{'pkg':36} {'mode':8} {'field_read':10} ok   max_ms")
for (pkg, mode, read), g in sorted(groups.items(), key=str):
    print(f"{str(pkg):36} {mode:8} {read:10} {sum(r['ok'] for r in g)}/{len(g)}  {max(r['ms'] for r in g)}")
PY
