#!/usr/bin/env bash
# Comprobaciones del APK y de la pantalla NATIVA del launcher. Sin emulador, sin red.
#
# POR QUÉ EXISTE: el repo se construye a mano (build.sh + aapt/d8/apksigner), así
# que no hay `gradle test` ni nada que una herramienta reconozca como verificación.
# Esto es ese punto de entrada: `make verify` (que además llama a los tests JVM).
#
# NO comprueba el comportamiento en el dispositivo: eso es `correspondencia-nativa.py`
# sobre el emulador, que lee lo que la pantalla dice haber dibujado.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
BT="$ANDROID_HOME/build-tools/34.0.0"
APK="$APP/build/vgate-bridge.apk"
SRC="$APP/src/com/cassiopeia/vgatebridge"
FIRMA="23d00c637f69f6e0b0008c38fdfd528d78ebc46b01198f61a43b5a9b4111198d"
fail=0; ok(){ echo "  ✅ $1"; }; bad(){ echo "  ❌ $1"; fail=1; }

[ -f "$APK" ] || { echo "FALTA el APK: ejecuta antes 'make build'"; exit 1; }

echo "── APK ──"
# La versión se lee del manifiesto: fijarla aquí envejece y da un fallo falso en
# cuanto sube la versión (pasó con la vc 37).
VC=$(grep -o 'android:versionCode="[0-9]*"' "$APP/AndroidManifest.xml" | head -1 | grep -o '[0-9]*')
VN=$(grep -o 'android:versionName="[^"]*"' "$APP/AndroidManifest.xml" | head -1 | sed 's/.*="//; s/"//')
"$BT/aapt" dump badging "$APK" 2>/dev/null | grep -q "versionCode='$VC'" \
    && ok "vc $VC / $VN (leída del manifiesto)" || bad "el APK no lleva la versión del manifiesto"
# La firma TIENE que ser la misma o el update falla y hay que desinstalar (perdería
# la MAC configurada del coche).
"$BT/apksigner" verify --print-certs "$APK" 2>/dev/null | grep -q "$FIRMA" \
    && ok "misma firma que la instalada" || bad "firma distinta: el update no actualizaría encima"
for c in LauncherActivity MediaListener LiveState CanSnifferService \
         InstrumentoView BarraView TestigoView MusicaView CajonView \
         Ajustes Apps MediaSesion Paleta Pizarra; do
    "$BT/dexdump" -f "$APP/build/classes.dex" 2>/dev/null | grep -q "Lcom/cassiopeia/vgatebridge/$c;" \
        && ok "$c en el DEX" || bad "falta $c en el DEX"
done
# El stub de TWUtil NO puede entrar en el DEX: taparía la clase real de la ROM y el
# sniffer CAN dejaría de leer.
[ "$("$BT/dexdump" -f "$APP/build/classes.dex" 2>/dev/null | grep -c "Class descriptor  : 'Landroid/tw")" = "0" ] \
    && ok "el stub TWUtil no está definido en el DEX" || bad "el stub taparía la clase de la ROM"
"$BT/aapt" dump xmltree "$APK" AndroidManifest.xml 2>/dev/null | grep -q "android.intent.category.HOME" \
    && ok "declarado como pantalla de inicio" || bad "sin categoría HOME"

echo "── Arquitectura: nada de WebView ──"
# Esta es LA comprobación de la migración (v5.4.0): la interfaz es nativa. Si alguien
# reintrodujera un WebView sin querer, esto lo dice antes de llegar a la tablet.
n=$(grep -rl "WebView" "$SRC" 2>/dev/null | wc -l)
[ "$n" = "0" ] && ok "ninguna clase usa WebView" || bad "hay $n clase(s) con WebView: $n (la interfaz debe ser nativa)"
[ ! -f "$SRC/WebBridge.java" ] && ok "el puente JavaScript ya no existe" || bad "WebBridge sigue ahí"
[ ! -d "$APP/assets/launcher" ] && ok "los ficheros HTML/CSS/JS ya no se empaquetan" || bad "quedan assets de la pantalla vieja"
[ "$(unzip -l "$APK" 2>/dev/null | grep -c 'assets/')" = "0" ] \
    && ok "el APK no lleva ningún asset" || bad "el APK lleva assets que ya no se usan"

echo "── La pantalla no puede mentir ──"
# Un dato ausente se pinta como raya, nunca como cero. Es la regla que más ha costado
# mantener en todo el proyecto (el caso MAP): un cuadro que inventa un cero es peor
# que uno que reconoce que no sabe.
grep -q '"—"' "$SRC/InstrumentoView.java" && ok "el instrumento ausente pinta raya, no cero" \
    || bad "el instrumento no distingue 'no lo sé' de cero"
grep -q 'CADUCA_MS' "$SRC/LauncherActivity.java" && ok "una medida vieja caduca y pasa a desconocida" \
    || bad "sin caducidad: un dato muerto se quedaría congelado en pantalla"
grep -q 'CADUCA_MS' "$SRC/LauncherActivity.java" \
    && grep -q 'fresco && st.coolantC' "$SRC/LauncherActivity.java" \
    && ok "los avisos por umbral se callan con dato viejo" \
    || bad "los avisos por umbral seguirían avisando con el dato congelado"

echo "── Instrumentos ──"
grep -q "textoEscala" "$SRC/InstrumentoView.java" && ok "las escalas llevan números, no solo marcas" \
    || bad "escalas sin números: la aguja no se puede leer"
grep -q "umbralRojo" "$SRC/InstrumentoView.java" && ok "la zona roja se dibuja solo por debajo de la aguja" \
    || bad "sin zona roja"
grep -q "Pizarra.anota" "$SRC/InstrumentoView.java" && ok "cada instrumento deja constancia de lo dibujado" \
    || bad "sin constancia: no se podría comprobar lo que se ve"

echo "── Tema día/noche ──"
# LUCIES ENCENDIDAS = NOCHE. Se comprueba la línea exacta porque ya nos equivocamos
# una vez: al pasar a nativo se invirtió y de noche el cuadro salía en claro.
grep -q "quiereNoche = Boolean.TRUE.equals(st.lightsOn);" "$SRC/LauncherActivity.java" \
    && ok "alumbrado encendido = noche (la lógica que ya funcionaba)" \
    || bad "lógica de tema cambiada o invertida: de noche saldría en claro"
grep -q "Paleta.de(noche)" "$SRC/LauncherActivity.java" && ok "los dos temas existen y se aplican" \
    || bad "sin cambio de tema"

echo "── Música, aplicaciones y salidas ──"
grep -q "getActiveSessions" "$SRC/MediaSesion.java" && ok "la música se lee de MediaSession (no de Spotify)" \
    || bad "la música no sale de MediaSession"
grep -q "SecurityException" "$SRC/MediaSesion.java" && ok "el permiso ausente se trata (no revienta)" \
    || bad "sin tratar el permiso ausente"
grep -q "permiso" "$SRC/MediaSesion.java" && grep -q "Conceder acceso" "$SRC/MusicaView.java" \
    && ok "sin permiso se explica y se ofrece concederlo (no un hueco mudo)" || bad "permiso sin salida"
grep -q "queryIntentActivities" "$SRC/Apps.java" && ok "las apps se leen del gestor de paquetes" \
    || bad "la lista de apps no sale del gestor de paquetes"
grep -q "loadIcon" "$SRC/Apps.java" && ok "se usan los iconos REALES de las aplicaciones" \
    || bad "sin iconos reales"
grep -q "getLaunchIntentForPackage" "$SRC/Apps.java" && ok "se valida que la app exista antes de abrirla" \
    || bad "abrir sin validar"
grep -q "getApplicationIcon" "$SRC/LauncherActivity.java" && ok "los accesos directos llevan icono real" \
    || bad "accesos sin icono"
grep -q "onBackPressed" "$SRC/LauncherActivity.java" && ok "atrás no deja la tablet sin interfaz" \
    || bad "atrás sin control"
grep -q "ajustesInicio" "$SRC/LauncherActivity.java" && ok "salida de emergencia al launcher de la ROM" \
    || bad "sin salida de emergencia"
grep -q "KEEP_SCREEN_ON" "$SRC/LauncherActivity.java" && ok "la pantalla no se apaga conduciendo" \
    || bad "sin mantener la pantalla encendida"
grep -q "hayEstadoInyectado" "$SRC/Ajustes.java" && ok "los datos de prueba solo entran si existe su fichero" \
    || bad "la inyección de pruebas no está condicionada"

echo "── Matices del empaquetado ──"
grep -q "A assets" "$APP/build.sh" && bad "build.sh sigue empaquetando assets que ya no existen" \
    || ok "build.sh no empaqueta assets"
# Las clases de la PANTALLA solo pueden leer de la carpeta de la propia app. Se
# excluyen los comentarios a propósito: la primera versión de esta comprobación
# marcaba como fallo cuatro comentarios que mencionaban /sdcard al explicar otras
# cosas. Un instrumento que miente es peor que no tenerlo.
fuera=$(grep -nE "getExternalStorageDirectory|/sdcard" \
        "$SRC/LauncherActivity.java" "$SRC/Ajustes.java" "$SRC/MediaSesion.java" \
        "$SRC/Apps.java" "$SRC/Pizarra.java" 2>/dev/null \
        | grep -vE ": *\*|: *//" | wc -l)
[ "$fuera" = "0" ] && ok "las clases de la pantalla no leen de fuera de la carpeta de la app" \
    || bad "hay $fuera línea(s) de la pantalla leyendo de fuera de la carpeta de la app"

echo
[ "$fail" = 0 ] && echo "RESULTADO: ✅ verificado" || echo "RESULTADO: ❌ hay fallos"
exit "$fail"
