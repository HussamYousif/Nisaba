#!/usr/bin/env bash
# Live check on the Boox: a fresh test notebook, the app's own hand writes QUESTION, Nisaba answers.
# Offline Boox: the model goes through scripts/dev_proxy.py over adb reverse.
# usage: scripts/device_ask.sh "question" [voice] [wait-seconds] [--canvas]
#        scripts/device_ask.sh --follow "more writing" [wait-seconds]   (into the notebook already open)
#        scripts/device_ask.sh --clean                                  (removes the check-… notebooks)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; source "$ROOT/scripts/env.sh" >/dev/null
PREFIX="check-"
adb shell input keyevent KEYCODE_WAKEUP
adb shell pm enable com.nisaba >/dev/null 2>&1 || true   # the Boox freezes apps it thinks are idle
if [ "${1:-}" = "--clean" ]; then
  adb shell am force-stop com.nisaba
  adb shell "am start -W -n com.nisaba/.app.MainActivity --es delete_prefix $PREFIX" >/dev/null; echo "test notebooks removed"; exit 0
fi
if [ "${1:-}" = "--follow" ]; then
  adb shell "am start -n com.nisaba/.app.MainActivity --activity-single-top --es write '$2'" >/dev/null
  sleep "${3:-40}"; OUT="$ROOT/captures/follow-$(date +%H%M%S).png"; adb exec-out screencap -p > "$OUT"; echo "$OUT"; exit 0
fi
Q="$1"; VOICE="${2:-book}"; WAIT="${3:-45}"; CANVAS=false; [ "${4:-}" = "--canvas" ] && CANVAS=true
if ! nc -z 127.0.0.1 8899 2>/dev/null; then
  nohup python3 "$ROOT/scripts/dev_proxy.py" > "$ROOT/captures/dev_proxy.log" 2>&1 & sleep 1
fi
adb reverse tcp:8899 tcp:8899 >/dev/null
NAME="$PREFIX$(date +%H%M%S)"
adb shell am force-stop com.nisaba
adb shell "am start -W -n com.nisaba/.app.MainActivity --ez pen_motion true --es proxy 127.0.0.1:8899 --es new_notebook '$NAME' --es persona $VOICE --ez canvas $CANVAS --es write '$Q'" >/dev/null
sleep "$WAIT"
mkdir -p "$ROOT/captures"; OUT="$ROOT/captures/$NAME.png"
adb exec-out screencap -p > "$OUT"; echo "$OUT"
