#!/usr/bin/env bash
# Prints the adb serial of the test phone (SM-S938U). More than one phone can be attached, and
# `aconnect serial` returns whichever it lists first, so the model decides.
set -euo pipefail

source ~/android-tooling/env.sh
aconnect >/dev/null 2>&1 || true
serial="$(adb devices -l | awk '/model:SM_S938U/ && $2 == "device" {print $1; exit}')"
[ -n "$serial" ] || { echo "SM-S938U is not attached" >&2; exit 1; }
echo "$serial"
