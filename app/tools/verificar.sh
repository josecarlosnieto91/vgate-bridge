#!/usr/bin/env bash
# Comprobaciones del APK y de la pantalla del launcher. Sin emulador, sin red.
#
# POR QUÉ EXISTE: el repo se construye a mano (build.sh + aapt/d8/apksigner), así
# que no hay `gradle test` ni nada que una herramienta reconozca como verificación.
# Esto es ese punto de entrada: `make verify` (que además llama a los tests JVM).
#
# NO comprueba el comportamiento en el dispositivo: eso es `make emulador`.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
BT="$ANDROID_HOME/build-tools/34.0.0"
APK="$APP/build/vgate-bridge.apk"
SRC="$APP/src/com/cassiopeia/vgatebridge"
JS="$APP/assets/launcher/launcher.js"; CSS="$APP/assets/launcher/launcher.css"
HR="$APP/assets/launcher/index.html"
FIRMA="23d00c637f69f6e0b0008c38fdfd528d78ebc46b01198f61a43b5a9b4111198d"
fail=0; ok(){ echo "  ✅ $1"; }; bad(){ echo "  ❌ $1"; fail=1; }

[ -f "$APK" ] || { echo "FALTA el APK: ejecuta antes 'make build'"; exit 1; }

echo "── APK ──"
# La versión se lee del manifiesto: fijarla aquí hace que esto envejezca y dé un
# fallo falso en cuanto sube la versión (pasó con la vc 37).
VC=$(grep -o 'android:versionCode="[0-9]*"' "$APP/AndroidManifest.xml" | head -1 | grep -o '[0-9]*')
VN=$(grep -o 'android:versionName="[^"]*"' "$APP/AndroidManifest.xml" | head -1 | sed 's/.*="//; s/"//')
"$BT/aapt" dump badging "$APK" 2>/dev/null | grep -q "versionCode='$VC'" \
    && ok "vc $VC / $VN (leída del manifiesto)" || bad "el APK no lleva la versión del manifiesto"
# La firma TIENE que ser la misma o el update falla y hay que desinstalar (perdería
# la MAC configurada del coche).
"$BT/apksigner" verify --print-certs "$APK" 2>/dev/null | grep -q "$FIRMA" \
    && ok "misma firma que la instalada" || bad "firma distinta: el update no actualizaría encima"
[ "$(unzip -l "$APK" 2>/dev/null | grep -c 'assets/launcher/')" = "3" ] \
    && ok "los 3 ficheros de pantalla van dentro" || bad "assets mal (¿copias de seguridad coladas?)"
for c in LauncherActivity WebBridge MediaListener LiveState CanSnifferService; do
    "$BT/dexdump" -f "$APP/build/classes.dex" 2>/dev/null | grep -q "Lcom/cassiopeia/vgatebridge/$c;" \
        && ok "$c en el DEX" || bad "falta $c en el DEX"
done
# El stub de TWUtil NO puede entrar en el DEX: taparía la clase real de la ROM.
[ "$("$BT/dexdump" -f "$APP/build/classes.dex" 2>/dev/null | grep -c "Class descriptor  : 'Landroid/tw")" = "0" ] \
    && ok "el stub TWUtil no está definido en el DEX" || bad "el stub taparía la clase de la ROM"
"$BT/aapt" dump xmltree "$APK" AndroidManifest.xml 2>/dev/null | grep -q "android.intent.category.HOME" \
    && ok "declarado como pantalla de inicio" || bad "sin categoría HOME"

echo "── Pantalla ──"
grep -q "setBlockNetworkLoads(true)" "$SRC/LauncherActivity.java" && ok "sin red (nada externo se cuela)" || bad "sin bloqueo de red"
grep -q "onBackPressed" "$SRC/LauncherActivity.java" && ok "atrás no deja la tablet sin interfaz" || bad "atrás sin control"
for e in reloj-hora m-titulo t-puertas-texto; do
    grep -q "$e" "$HR" || bad "falta el elemento $e en la pantalla"
done
ok "elementos de la pantalla presentes"
grep -qE "var (DIAS|MESES) *= *\[" "$JS" && ok "reloj local, sin red" || bad "reloj mal"
grep -q "permiso === false" "$JS" && ok "música sin permiso: aviso propio, no un hueco mudo" || bad "sin estado de permiso"
grep -q "plegada" "$CSS" && grep -q "plegada" "$JS" && ok "sin reproducción la zona se pliega" || bad "zona sin plegar"
grep -q "SecurityException" "$SRC/WebBridge.java" && ok "permiso ausente tratado (no revienta)" || bad "sin tratar"
grep -q "getLaunchIntentForPackage" "$SRC/WebBridge.java" && ok "abrirApp valida que la app exista" || bad "abrirApp sin validar"
grep -q "ajustesInicio" "$SRC/WebBridge.java" && ok "salida de emergencia al launcher de la ROM" || bad "sin salida de emergencia"

echo "── Compatibilidad con el WebView de Android 10 (Chromium 74) ──"
# `gap` en FLEX no existe hasta Chromium 84; en GRID sí (desde el 57). Se analiza
# regla por regla: mirar "las 8 líneas anteriores" da falsos positivos —una regla
# de grid con gap precedida de otra con display:flex se marcaba como error— y eso
# ya nos costó una tarde. Un instrumento que miente es peor que no tenerlo.
flex_con_gap=$(python3 - "$CSS" <<'PY'
import re, sys
css = open(sys.argv[1]).read()
malos = []
for bloque in re.finditer(r"([^{}]+)\{([^}]*)\}", css):
    selector = bloque.group(1).strip().splitlines()[-1].strip()
    cuerpo = bloque.group(2)
    if re.search(r"(^|;|\s)gap\s*:", cuerpo) and re.search(r"display\s*:\s*flex", cuerpo):
        malos.append(selector)
print(" ".join(malos))
PY
)
[ -z "$flex_con_gap" ] \
    && ok "ningún 'gap' en flex (los de grid sí valen en Chromium 74)" \
    || bad "'gap' en contenedor flex (no funciona en Chromium 74): $flex_con_gap"
grep -qE "=>|\\\$\{" "$JS" \
    && bad "flechas de función o plantillas de texto en el JS (Chromium 74 no las traga)" \
    || ok "JS conservador: sin flechas ni plantillas"

echo
[ "$fail" = 0 ] && echo "RESULTADO: ✅ verificado" || echo "RESULTADO: ❌ hay fallos"
exit "$fail"
