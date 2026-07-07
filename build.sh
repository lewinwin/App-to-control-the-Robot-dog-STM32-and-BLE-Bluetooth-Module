#!/usr/bin/env bash
set -euo pipefail

# --- Locations -------------------------------------------------------------
PROJ="$(cd "$(dirname "$0")" && pwd)"
SDK="$LOCALAPPDATA/Android/Sdk"
BT="$SDK/build-tools/36.0.0"
ANDROID_JAR="$SDK/platforms/android-36.1/android.jar"

AAPT2="$BT/aapt2.exe"
D8="$BT/d8.bat"
ZIPALIGN="$BT/zipalign.exe"
APKSIGNER="$BT/apksigner.bat"

BUILD="$PROJ/build"
GEN="$BUILD/gen"
OBJ="$BUILD/obj"
DEX="$BUILD/dex"
RESZIP="$BUILD/res.zip"
UNSIGNED="$BUILD/app-unsigned.apk"
ALIGNED="$BUILD/app-aligned.apk"
FINAL="$PROJ/STM32-BT-Control.apk"
KEYSTORE="$BUILD/debug.keystore"

rm -rf "$BUILD"
mkdir -p "$GEN" "$OBJ" "$DEX" "$BUILD/compiled-res"

echo "==> [1/6] Compiling resources (aapt2 compile)"
"$AAPT2" compile --dir "$PROJ/res" -o "$BUILD/compiled-res.zip"

echo "==> [2/6] Linking resources (aapt2 link) + generating R.java"
"$AAPT2" link \
  -o "$RESZIP" \
  -I "$ANDROID_JAR" \
  --manifest "$PROJ/AndroidManifest.xml" \
  --java "$GEN" \
  --min-sdk-version 21 \
  --target-sdk-version 36 \
  "$BUILD/compiled-res.zip"

echo "==> [3/6] Compiling Java (javac)"
JAVAFILES=$(find "$PROJ/src" "$GEN" -name '*.java')
javac -source 8 -target 8 \
  -bootclasspath "$ANDROID_JAR" \
  -d "$OBJ" \
  -nowarn -Xlint:none \
  $JAVAFILES

echo "==> [4/6] Dexing (d8)"
CLASSFILES=$(find "$OBJ" -name '*.class')
"$D8" --min-api 21 --lib "$ANDROID_JAR" --output "$DEX" $CLASSFILES

echo "==> [5/6] Packaging APK"
cp "$RESZIP" "$UNSIGNED"
( cd "$DEX" && "$ZIPALIGN" >/dev/null 2>&1 || true )
# add classes.dex into the resource apk (which is a zip)
( cd "$DEX" && "$SDK/build-tools/36.0.0/aapt2.exe" version >/dev/null 2>&1 || true )
# use jar to insert dex at archive root
( cd "$DEX" && jar uf "$UNSIGNED" classes.dex )

echo "==> [5b/6] zipalign"
"$ZIPALIGN" -f -p 4 "$UNSIGNED" "$ALIGNED"

echo "==> [6/6] Signing"
if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -v \
    -keystore "$KEYSTORE" \
    -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass android -keypass android \
    -dname "CN=Android Debug,O=Android,C=US"
fi
"$APKSIGNER" sign \
  --ks "$KEYSTORE" \
  --ks-key-alias androiddebugkey \
  --ks-pass pass:android \
  --key-pass pass:android \
  --out "$FINAL" \
  "$ALIGNED"

echo
echo "==> DONE: $FINAL"
"$APKSIGNER" verify --verbose "$FINAL" | head -6
