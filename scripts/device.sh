#!/usr/bin/env bash
# scripts/device.sh install|log|shot|props|start
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/env.sh"
PKG=com.nisaba
case "${1:-}" in
  install) (cd "$ROOT" && ./gradlew :app:installDebug) ;;
  start)   adb shell am start -n "$PKG/.app.MainActivity" ;;
  log)     adb logcat -v time -s 'Nisaba*:V' 'AndroidRuntime:E' ;;
  shot)    mkdir -p "$ROOT/captures"; adb exec-out screencap -p > "$ROOT/captures/$(date +%H%M%S).png"; echo saved ;;
  props)   for p in ro.build.version.release ro.build.display.id ro.product.model; do echo "$p=$(adb shell getprop $p)"; done
           adb shell wm size; adb shell wm density ;;
  *) echo "usage: $0 install|start|log|shot|props"; exit 1 ;;
esac
