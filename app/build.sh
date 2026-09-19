#!/usr/bin/env bash
# Build script for VgateBridge APK
set -e

ANDROID_SDK="${ANDROID_SDK:-$HOME/android-sdk}"
JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk}"
PLATFORM="$ANDROID_SDK/platforms/android-29"
BUILD_TOOLS="$ANDROID_SDK/build-tools/34.0.0"
AAPT="$BUILD_TOOLS/aapt"
SRC="src/com/cassiopeia/vgatebridge"
# Stub de compilación de la clase de la ROM `android.tw.john.TWUtil` (no está en
# la SDK pública). Se compila CON el resto pero NO entra en el DEX: si entrara,
# taparía en runtime la implementación real de la ROM y el sniffing CAN moriría.
# Solo se le pasan a d8 las clases de la app. Ruta relativa a la raíz del repo
# (este script vive en app/), calculada para que dé igual desde dónde se llame.
STUB="$(cd "$(dirname "$0")/.." && pwd)/build/stub"

echo "=== Compiling Java sources ==="
rm -rf build/classes
mkdir -p build/classes

$JAVA_HOME/bin/javac -d build/classes \
    -bootclasspath $PLATFORM/android.jar \
    -source 8 -target 8 \
    $SRC/*.java \
    $STUB/android/tw/john/TWUtil.java

echo "=== Creating DEX ==="
$BUILD_TOOLS/d8 --release --output build/ \
    build/classes/com/cassiopeia/vgatebridge/*.class

echo "=== Packaging APK ==="
# -A assets: la pantalla del launcher (v5.1.0) vive en app/assets/launcher y tiene
# que viajar DENTRO del APK. Sin esto, el WebView buscaría un fichero que no existe
# y la pantalla de inicio saldría en negro.
[ -d assets/launcher ] || { echo "FALLO: falta assets/launcher (la pantalla del launcher)"; exit 1; }
"$AAPT" package -f \
    -M AndroidManifest.xml \
    -A assets \
    -I $PLATFORM/android.jar \
    -F build/vgate-bridge-unaligned.apk

echo "=== Adding DEX ==="
cd build
"$AAPT" add vgate-bridge-unaligned.apk classes.dex

echo "=== Signing ==="
# ⚠️ La clave debe ser LA MISMA que firmó la versión instalada, o el update
# falla por firma distinta (habría que desinstalar y se perdería la MAC
# configurada). La estándar de depuración es la que se usó hasta ahora.
KEYSTORE="${ANDROID_KEYSTORE:-$HOME/.android/debug.keystore}"
KEY_ALIAS="${ANDROID_KEY_ALIAS:-androiddebugkey}"
[ -f "$KEYSTORE" ] || keytool -genkey -v -keystore "$KEYSTORE" \
    -alias debug -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass android -keypass android -dname "CN=Debug"

$BUILD_TOOLS/apksigner sign --ks "$KEYSTORE" \
    --ks-pass pass:android --ks-key-alias "${KEY_ALIAS:-androiddebugkey}" \
    vgate-bridge-unaligned.apk

cp vgate-bridge-unaligned.apk vgate-bridge.apk
echo ""
echo "=== APK ready ==="
ls -la vgate-bridge.apk
