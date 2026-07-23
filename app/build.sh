#!/usr/bin/env bash
# Build script for VgateBridge APK
set -e

ANDROID_SDK="${ANDROID_SDK:-$HOME/android-sdk}"
JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk}"
PLATFORM="$ANDROID_SDK/platforms/android-29"
BUILD_TOOLS="$ANDROID_SDK/build-tools/34.0.0"
SRC="src/com/cassiopeia/vgatebridge"

echo "=== Compiling Java sources ==="
rm -rf build/classes
mkdir -p build/classes

$JAVA_HOME/bin/javac -d build/classes \
    -bootclasspath $PLATFORM/android.jar \
    -source 8 -target 8 \
    $SRC/*.java

echo "=== Creating DEX ==="
$BUILD_TOOLS/d8 --release --output build/ \
    build/classes/com/cassiopeia/vgatebridge/*.class

echo "=== Packaging APK ==="
$aapt package -f \
    -M AndroidManifest.xml \
    -I $PLATFORM/android.jar \
    -F build/vgate-bridge-unaligned.apk

echo "=== Adding DEX ==="
cd build
$aapt add vgate-bridge-unaligned.apk classes.dex

echo "=== Signing ==="
KEYSTORE="debug.keystore"
[ -f "$KEYSTORE" ] || keytool -genkey -v -keystore "$KEYSTORE" \
    -alias debug -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass android -keypass android -dname "CN=Debug"

$BUILD_TOOLS/apksigner sign --ks "$KEYSTORE" \
    --ks-pass pass:android --ks-key-alias debug \
    vgate-bridge-unaligned.apk

cp vgate-bridge-unaligned.apk vgate-bridge.apk
echo ""
echo "=== APK ready ==="
ls -la vgate-bridge.apk
