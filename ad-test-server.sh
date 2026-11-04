#!/usr/bin/env bash
# Serves ads/local/ at http://127.0.0.1:8765 and points the phone (over USB) at ads/local/ad.json.
#   ./ad-test-server.sh            build + install a debug app that reads the local config, then serve
#   ./ad-test-server.sh --no-build only serve (app already installed by this script)
# Edit ads/local/ad.json while it runs; reopen the player to see the change.
set -euo pipefail

PORT=8765
ROOT="$(cd "$(dirname "$0")" && pwd)"
URL="http://127.0.0.1:${PORT}/ad.json"

command -v adb >/dev/null || { echo "adb not found"; exit 1; }
adb get-state >/dev/null 2>&1 || { echo "No device connected. Enable USB debugging and connect the phone."; exit 1; }

if [[ "${1:-}" != "--no-build" ]]; then
  echo "==> Building debug app with REMOTE_AD_CONFIG_URL=${URL}"
  (cd "$ROOT" && ./gradlew assembleDebug -q -PREMOTE_AD_CONFIG_URL="$URL")
  adb install -r "$ROOT/app/build/outputs/apk/debug/app-debug.apk"
fi

adb reverse tcp:${PORT} tcp:${PORT}
trap 'adb reverse --remove tcp:${PORT} >/dev/null 2>&1 || true' EXIT

adb shell am start -n com.mantra.player/.MainActivity >/dev/null
echo
echo "==> Ad config URL (Mac and phone): ${URL}"
echo "    Edit:     ads/local/ad.json"
echo "    Samples:  ads/local/samples/  (e.g. cp ads/local/samples/off.json ads/local/ad.json)"
echo "    Stop with Ctrl+C, then run ./build-and-push.sh to go back to the normal build."
echo
cd "$ROOT/ads/local" && python3 -m http.server "$PORT" --bind 127.0.0.1
