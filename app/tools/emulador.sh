#!/usr/bin/env bash
# Entorno de pruebas local de la app: emulador Android 10 (API 29).
#
# POR QUÉ API 29 Y NO OTRA: la tablet del coche es Android 10. Su WebView es
# Chromium 74, y ahí `gap` en contenedores FLEX no existe (llegó en el 84). Ese
# detalle no se ve en un navegador de escritorio ni en un móvil moderno: solo
# aparece probando en la versión real. Para eso está este entorno.
#
# Uso:
#   bash app/tools/emulador.sh crear     # crea el AVD si no existe
#   bash app/tools/emulador.sh arrancar  # lo arranca (sin ventana, acelerado)
#   bash app/tools/emulador.sh probar    # instala el APK, lo deja como inicio y comprueba
#   bash app/tools/emulador.sh parar
set -eu

ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
export ANDROID_HOME ANDROID_SDK_ROOT="$ANDROID_HOME"
PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
AVD="${AVD:-polarstar-api29}"
IMAGEN="system-images;android-29;google_apis;x86_64"
PAQUETE="com.cassiopeia.vgatebridge"
APK="$(cd "$(dirname "$0")/.." && pwd)/build/vgate-bridge.apk"

crear() {
    if "$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" list avd 2>/dev/null | grep -q "Name: $AVD"; then
        echo "el AVD $AVD ya existe"
        return
    fi
    echo "=== instalando imagen de Android 10 (si falta) ==="
    yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$ANDROID_HOME" "$IMAGEN" "platforms;android-29" >/dev/null
    echo "=== creando el AVD ==="
    echo "no" | "$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" create avd \
        -n "$AVD" -k "$IMAGEN" -d "7in WSVGA (Tablet)" --force
    # Cómodidad: 512 MB no dan ni para el WebView, y la partición temporal pierde
    # la app en cada arranque (molesto para desarrollar).
    CFG="$HOME/.android/avd/$AVD.avd/config.ini"
    python3 - "$CFG" <<'PY'
import sys
p = sys.argv[1]
lineas = [l for l in open(p).read().splitlines()
          if not l.startswith(("disk.dataPartition", "hw.ramSize", "hw.initialOrientation",
                               "hw.gpu.enabled", "hw.gpu.mode", "vm.heapSize"))]
lineas += ["hw.ramSize=2048", "vm.heapSize=256", "hw.gpu.enabled=yes", "hw.gpu.mode=host",
           "hw.initialOrientation=landscape", "disk.dataPartition.size=2048M", "hw.keyboard=yes"]
open(p, "w").write("\n".join(lineas) + "\n")
PY
    echo "AVD $AVD listo"
}

arrancar() {
    echo "=== arrancando $AVD (sin ventana) ==="
    "$ANDROID_HOME/emulator/emulator" -avd "$AVD" -no-window -no-audio -no-boot-anim \
        -no-snapshot-save -gpu swiftshader_indirect &
    adb wait-for-device
    for _ in $(seq 1 40); do
        [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
        sleep 6
    done
    echo "Android $(adb shell getprop ro.build.version.release | tr -d '\r') (API $(adb shell getprop ro.build.version.sdk | tr -d '\r')) listo"
    echo "WebView del sistema: $(adb shell dumpsys package com.google.android.webview 2>/dev/null | grep -m1 versionName | tr -d '\r' | cut -d= -f2)"
}

probar() {
    [ -f "$APK" ] || { echo "FALTA el APK: ejecuta antes bash app/build.sh"; exit 1; }
    echo "=== instalando ==="
    adb install -r "$APK" | tail -1
    # El aviso de pantalla completa de Android 10 sale UNA vez por instalación y
    # tapa la pantalla en las capturas. Se silencia para poder revisar de verdad.
    adb shell settings put secure immersive_mode_confirmations confirmed >/dev/null 2>&1
    adb logcat -c
    adb shell am start -n "$PAQUETE/.LauncherActivity" >/dev/null
    sleep 8
    echo "=== en primer plano ==="
    adb shell dumpsys activity activities 2>/dev/null | grep -m1 mResumedActivity | tr -d '\r'
    echo "=== ¿crash? (0 = bien) ==="
    adb logcat -d 2>/dev/null | grep -cE "FATAL EXCEPTION|E AndroidRuntime" || true
    echo "=== dejándolo como pantalla de inicio ==="
    adb shell cmd package set-home-activity "$PAQUETE/.LauncherActivity" | tail -1
    adb shell input keyevent KEYCODE_HOME
    sleep 3
    adb shell dumpsys activity activities 2>/dev/null | grep -m1 mResumedActivity | tr -d '\r'
}

parar() { adb emu kill 2>/dev/null || pkill -f "emulator -avd $AVD" || true; echo "emulador parado"; }

case "${1:-probar}" in
    crear) crear ;;
    arrancar) arrancar ;;
    probar) probar ;;
    parar) parar ;;
    *) echo "uso: $0 {crear|arrancar|probar|parar}"; exit 1 ;;
esac
