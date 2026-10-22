#!/usr/bin/env bash
# Builds DebloatTV.apk with the Android SDK's own tools (no Gradle): aapt2, javac, d8, zipalign, apksigner.
# Needs a JDK 17+ and the Android SDK (platform android-34, build-tools 34). Set ANDROID_HOME and JAVA_HOME if they
# aren't found.
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
[ -d "$SDK/build-tools" ] || { echo "Android SDK not found: set ANDROID_HOME." >&2; exit 1; }
TOOLS="$SDK/build-tools/$(ls "$SDK/build-tools" | sort -V | tail -1)"
PLATFORM="$(ls -d "$SDK"/platforms/android-*/android.jar | sort -V | tail -1)"
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}"

rm -rf build && mkdir -p build/classes build/dex
"$TOOLS/aapt2" compile --dir app/res -o build/res.zip
"$TOOLS/aapt2" link -o build/base.apk -I "$PLATFORM" --manifest app/AndroidManifest.xml --java build/gen \
  -A app/assets --min-sdk-version 26 --target-sdk-version 33 -0 ttf build/res.zip
"${JAVA_BIN}javac" -nowarn -encoding UTF-8 --release 11 -cp "$PLATFORM" -d build/classes \
  $(find app/src build/gen -name '*.java') 2>&1 | grep -v '^Note:' || true
[ -n "$(find build/classes -name '*.class' | head -1)" ] || { echo "Compile failed." >&2; exit 1; }
"$TOOLS/d8" --lib "$PLATFORM" --min-api 26 --output build/dex $(find build/classes -name '*.class')
cp build/base.apk build/unsigned.apk
(cd build/dex && zip -q ../unsigned.apk classes.dex)
"$TOOLS/zipalign" -f 4 build/unsigned.apk build/aligned.apk

# Your own signing key, made once. Updates must be signed with the same key: keep debloat.keystore.
if [ ! -f debloat.keystore ]; then
  "${JAVA_BIN}keytool" -genkeypair -keystore debloat.keystore -storepass debloat -keypass debloat -alias debloat \
    -keyalg RSA -keysize 2048 -validity 20000 -dname "CN=Debloat TV" >/dev/null
fi
"$TOOLS/apksigner" sign --ks debloat.keystore --ks-pass pass:debloat --key-pass pass:debloat --out DebloatTV.apk build/aligned.apk
echo "Built DebloatTV.apk ($(du -h DebloatTV.apk | cut -f1))."
