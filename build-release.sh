#!/usr/bin/env bash
# Builds the signed release app with your real AdMob IDs (from gradle.properties).
#   ./build-release.sh             build APK + Play bundle, install the APK on the connected phone
#   ./build-release.sh --no-install build only
# First run creates the signing key (mantra-release.jks + keystore.properties). Back both up:
# without them you can never update the app on Google Play.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PACKAGE_NAME="com.mantra.player"
KEYSTORE="$ROOT/mantra-release.jks"
KEY_PROPS="$ROOT/keystore.properties"
KEY_ALIAS="mantra"
APK_PATH="$ROOT/app/build/outputs/apk/release/app-release.apk"
AAB_PATH="$ROOT/app/build/outputs/bundle/release/app-release.aab"
INSTALL=true

case "${1:-}" in
  --no-install) INSTALL=false ;;
  "") ;;
  *) echo "Usage: $(basename "$0") [--no-install]" >&2; exit 1 ;;
esac

cd "$ROOT"

if ! grep -q '^ADMOB_APP_ID=' gradle.properties || ! grep -q '^ADMOB_NATIVE_ID=' gradle.properties; then
  echo "Warning: ADMOB_APP_ID / ADMOB_NATIVE_ID not set in gradle.properties; release will use Google test ads."
fi

if [[ ! -f "$KEY_PROPS" ]]; then
  command -v keytool >/dev/null || { echo "keytool not found (install JDK 17)"; exit 1; }
  echo "==> No signing key yet. Creating $KEYSTORE"
  echo "    Choose a password (min 6 characters) and store it in a password manager."
  while true; do
    read -rsp "    Key password: " PASS; echo
    read -rsp "    Repeat password: " PASS2; echo
    [[ "$PASS" == "$PASS2" && ${#PASS} -ge 6 ]] && break
    echo "    Passwords don't match or are shorter than 6 characters; try again."
  done
  read -rp "    Your name or company (shown in the certificate): " OWNER
  keytool -genkeypair -v \
    -keystore "$KEYSTORE" -alias "$KEY_ALIAS" \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$PASS" -keypass "$PASS" \
    -dname "CN=${OWNER:-Mantra}, C=IN"
  cat > "$KEY_PROPS" <<EOF
storeFile=mantra-release.jks
storePassword=$PASS
keyAlias=$KEY_ALIAS
keyPassword=$PASS
EOF
  chmod 600 "$KEY_PROPS" "$KEYSTORE"
  echo "==> Created mantra-release.jks and keystore.properties. BACK UP BOTH FILES NOW."
fi

MP3_DIR="$ROOT/mp3"
RAW_DIR="$ROOT/app/src/main/res/raw"
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
    [[ -f "$MP3_DIR/$name.mp3" ]] || { echo "Error: missing $MP3_DIR/$name.mp3" >&2; exit 1; }
    cp -f "$MP3_DIR/$name.mp3" "$RAW_DIR/$name.mp3"
  done
fi

echo "==> Building signed release APK and Play bundle..."
./gradlew assembleRelease bundleRelease

cp -f "$APK_PATH" "$ROOT/Mantra-release.apk"
cp -f "$AAB_PATH" "$ROOT/Mantra-release.aab"
echo "==> APK (install on phones):     $ROOT/Mantra-release.apk"
echo "==> Bundle (upload to Play):     $ROOT/Mantra-release.aab"

$INSTALL || exit 0

command -v adb >/dev/null || { echo "adb not found; skipping install."; exit 0; }
adb get-state >/dev/null 2>&1 || { echo "No device connected; skipping install."; exit 0; }

echo "==> Installing on device..."
if ! OUTPUT="$(adb install -r "$APK_PATH" 2>&1)"; then
  echo "$OUTPUT"
  if grep -q "INSTALL_FAILED_UPDATE_INCOMPATIBLE" <<<"$OUTPUT"; then
    echo
    echo "The phone has the debug build, which is signed with a different key."
    read -rp "Uninstall it (clears the app's saved settings on the phone) and install release? [y/N] " ANSWER
    [[ "$ANSWER" =~ ^[Yy]$ ]] || exit 1
    adb uninstall "$PACKAGE_NAME"
    adb install "$APK_PATH"
  else
    exit 1
  fi
fi

adb shell am start -n "$PACKAGE_NAME/.MainActivity" >/dev/null
echo "Done. Release build installed. Your phone is a test device, so it should show \"Test Ad\" labels."
