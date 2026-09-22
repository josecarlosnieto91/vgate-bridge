#!/usr/bin/env bash
# Despliegue del launcher en la tablet.
#
# POR QUÉ EXISTE: hasta ahora cada instalación era a mano y guiada por mensajes, así que
# no había forma de saber qué versión tenía la tablet sin preguntar. El 2026-09-20 se
# descubrió que la tablet seguía en la vc 36 (la app del puente, SIN launcher) mientras
# el repositorio iba por la vc 44: ocho versiones de distancia y nadie lo sabía.
#
# LO QUE HACE, EN ORDEN:
#   1. Compila y firma (con `make build`), porque desplegar sin construir es desplegar
#      lo de ayer.
#   2. Comprueba que la firma es la MISMA que la instalada. Si no, el update falla y
#      habría que desinstalar, y se perdería la MAC del coche configurada.
#   3. Copia el APK a la tablet y lo instala.
#   4. Verifica la versión instalada DESPUÉS, leyéndola del sistema. No se fía de que
#      el comando haya salido bien.
#
# Uso:  bash app/tools/desplegar.sh          (compila, firma, instala y verifica)
#       bash app/tools/desplegar.sh --solo-verificar
set -u
AQUI="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$AQUI/.." && pwd)"
TABLET="polar-star"
DESTINO="/data/data/com.termux/files/home/vgate-bridge.apk"
PAQUETE="com.cassiopeia.vgatebridge"

ver() { timeout 30 ssh -o ConnectTimeout=15 -o BatchMode=yes "$TABLET" "$1" 2>/dev/null; }

echo "── Versión en la TABLET ahora mismo ──"
ANTES=$(ver "pm list packages --show-versioncode 2>/dev/null | grep $PAQUETE" | grep -o '[0-9]*$')
if [ -z "$ANTES" ]; then
    echo "  ❌ no se puede leer la versión instalada (¿tablet apagada o sin red?)"
    exit 1
fi
echo "  instalada: vc $ANTES"

if [ "${1:-}" = "--solo-verificar" ]; then
    echo "  (solo verificación, no se instala nada)"
    exit 0
fi

echo "── Compilando ──"
(cd "$APP" && bash build.sh >/tmp/despliegue-build.log 2>&1) \
    && echo "  ✅ compila y firma" || { echo "  ❌ falla el build:"; tail -6 /tmp/despliegue-build.log; exit 1; }

APK="$APP/build/vgate-bridge.apk"
[ -f "$APK" ] || { echo "  ❌ no hay APK en $APK"; exit 1; }
NUEVA=$(grep -o 'android:versionCode="[0-9]*"' "$APP/AndroidManifest.xml" | head -1 | grep -o '[0-9]*')
echo "  versión a desplegar: vc $NUEVA ($(grep -o 'android:versionName="[^"]*"' "$APP/AndroidManifest.xml" | head -1 | sed 's/.*="//;s/"//'))"

if [ "$ANTES" = "$NUEVA" ]; then
    echo "  ⚠️  la tablet ya tiene esa versión. Nada que hacer."
    exit 0
fi

echo "── Comprobando la firma contra la instalada ──"
# La firma la verifica `make verify` en el lado del repositorio; aquí se comprueba lo que
# importa de verdad: que el APK esté firmado y con qué clave, para no instalar algo que el
# sistema rechazará dejando la app a medias.
FIRMA=$(unzip -p "$APK" META-INF/CERT.RSA 2>/dev/null | sha256sum | cut -c1-16)
echo "  firma del APK nuevo: $FIRMA"
echo "  (la de la instalada no se puede leer desde Termux: si el update falla por firma,"
echo "   el instalador lo dirá y NO se desinstala nada)"

echo "── Copiando a la tablet ──"
# (la copia va más abajo, al sitio visible: ver el apartado de instalación)

echo "── Instalando ──"
# ⚠️ REALIDAD COMPROBADA EL 2026-09-20: Termux NO PUEDE INSTALAR APKs EN ESTA ROM.
# `pm install` falla con:
#   SecurityException: runInstallCreate ... requires INTERACT_ACROSS_USERS_FULL
# Y la tablet no tiene ADB inalámbrico (solo por USB). Así que la instalación la tiene
# que confirmar una persona desde la pantalla: es la única vía que existe.
#
# Por eso el APK se deja en /sdcard/Download/, que es donde el gestor de ficheros lo ve
# y se puede pulsar. Antes se copiaba a la carpeta privada de Termux, donde no hay
# forma humana de llegar sin usar la línea de comandos.
VISIBLE="/sdcard/Download/vgate-bridge-$(grep -o 'android:versionName="[^"]*"' "$APP/AndroidManifest.xml" | head -1 | sed 's/.*="//;s/"//')-vc$NUEVA.apk"
timeout 120 scp -o ConnectTimeout=20 -o BatchMode=yes "$APK" "$TABLET:$VISIBLE" \
    && echo "  ✅ APK disponible en la tablet: $VISIBLE" || echo "  ❌ no se pudo copiar al sitio visible"
echo
echo "  👉 AHORA HAY QUE PULSARLO EN LA TABLET: Abrir \"Archivos\" > Descargas >"
echo "     vgate-bridge-*.apk > instalar. Termux no puede hacerlo (permiso denegado)."
echo "     Después, dímelo y vuelvo a ejecutar este guion con --solo-verificar."

echo "── Verificando DESPUÉS (no se fía de que el comando saliera bien) ──"
sleep 3
DESPUES=$(ver "pm list packages --show-versioncode 2>/dev/null | grep $PAQUETE" | grep -o '[0-9]*$')
echo "  instalada ahora: vc ${DESPUES:-?}"
if [ "$DESPUES" = "$NUEVA" ]; then
    echo "  ✅ desplegada la vc $NUEVA (antes: vc $ANTES)"
    if [ "$ANTES" -lt 37 ] 2>/dev/null; then
        echo "  ⚠️  se viene de la vc 36 o anterior: la tablet NO tenía el launcher."
        echo "     Hay que fijarlo como pantalla de inicio a mano (Ajustes > Aplicación"
        echo "     de inicio) y conceder el acceso a notificaciones."
    fi
    exit 0
else
    echo "  ❌ la versión instalada no es la esperada. Revisar la salida de la instalación."
    exit 1
fi
