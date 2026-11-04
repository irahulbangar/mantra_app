#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APK_PATH="$ROOT_DIR/app/build/outputs/apk/debug/app-debug.apk"
COPY_PATH="$ROOT_DIR/Mantra-debug.apk"
PACKAGE_NAME="com.mantra.player"
MAIN_ACTIVITY="$PACKAGE_NAME/.MainActivity"

LAUNCH_APP=true

usage() {
  cat <<EOF
Usage: $(basename "$0") [options]

Build the debug APK and install it on a connected Android device via adb.

Options:
  --no-launch    Install only; do not launch the app
  -h, --help     Show this help message

Requirements:
  - JDK 17
  - adb in PATH
  - Device connected with USB debugging enabled
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --no-launch)
      LAUNCH_APP=false
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

if ! command -v adb >/dev/null 2>&1; then
  echo "Error: adb not found. Install Android platform-tools and add adb to PATH." >&2
  exit 1
fi

DEVICE_COUNT="$(adb devices | awk 'NR > 1 && $2 == "device" { count++ } END { print count + 0 }')"
if [[ "$DEVICE_COUNT" -eq 0 ]]; then
  echo "Error: No Android device connected. Enable USB debugging and reconnect." >&2
  adb devices
  exit 1
fi

if [[ "$DEVICE_COUNT" -gt 1 ]]; then
  echo "Warning: Multiple devices detected. adb will use the default selected device."
  adb devices
fi

cd "$ROOT_DIR"

if [[ ! -x "$ROOT_DIR/gradlew" ]]; then
  echo "Error: gradlew not found or not executable in $ROOT_DIR" >&2
  exit 1
fi

MP3_DIR="$ROOT_DIR/mp3"
RAW_DIR="$ROOT_DIR/app/src/main/res/raw"
ACTIVE_MP3S=(
  gayatri_mantra
  om_gan_ganpataye_namo_namah
  om_namah_shivaya
  om_tryambakam_yajamahe_sugandhim_pushtivardhanam
  hanuman_chalisa
  santan_gopal_mantra
  nishank_hoi_re_mana
  shri_dattatreya_kavach
  swami_samarth_mantra
  hanuman_mantra
  sai_mantra
  lakshmi_kuber
  balaji_mantra
  shani_mantra
)

if [[ -d "$MP3_DIR" ]]; then
  echo "==> Syncing mp3/ into app raw resources..."
  mkdir -p "$RAW_DIR"
  for name in "${ACTIVE_MP3S[@]}"; do
    src="$MP3_DIR/$name.mp3"
    if [[ ! -f "$src" ]]; then
      echo "Error: missing $src" >&2
      exit 1
    fi
    cp -f "$src" "$RAW_DIR/$name.mp3"
  done
fi

echo "==> Building debug APK..."
"$ROOT_DIR/gradlew" assembleDebug

if [[ ! -f "$APK_PATH" ]]; then
  echo "Error: APK not found at $APK_PATH" >&2
  exit 1
fi

cp -f "$APK_PATH" "$COPY_PATH"
echo "==> APK ready: $COPY_PATH"

echo "==> Installing on device..."
adb install -r "$APK_PATH"

if [[ "$LAUNCH_APP" == true ]]; then
  echo "==> Launching app..."
  adb shell am start -n "$MAIN_ACTIVITY"
fi

echo "Done. Installed $PACKAGE_NAME"
