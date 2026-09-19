#!/usr/bin/env bash
# Tests JVM del APK del launcher. NO necesitan Android, ni emulador, ni tablet:
# las clases que se prueban son puras a propósito.
#
# DESCUBRE LOS TESTS, NO LOS LISTA. La versión anterior llevaba los ficheros escritos
# a mano, así que al añadir ViajeTest.java el guion siguió diciendo "todos los tests
# JVM: OK" sin ejecutarlo: un falso verde, que es la peor clase de error que puede
# cometer una herramienta de verificación. Ahora se compila y se ejecuta TODO lo que
# termine en Test.java, y si no encuentra ninguno, falla.
#
# Uso: bash app/tests/run.sh      (desde cualquier sitio)
set -eu
AQUI="$(cd "$(dirname "$0")" && pwd)"
SRC="$AQUI/../src/com/cassiopeia/vgatebridge"
SALIDA="$(mktemp -d)"
trap 'rm -rf "$SALIDA"' EXIT

# ── Qué se prueba ────────────────────────────────────────────────────────────
shopt -s nullglob
TESTS=("$AQUI"/*Test.java)
shopt -u nullglob
if [ "${#TESTS[@]}" -eq 0 ]; then
    echo "FALLO: no hay ningún *Test.java en $AQUI"
    exit 1
fi

# Las clases puras que necesitan los tests. Se listan por clases, no por ficheros: si
# alguien añade una clase pura nueva, aquí se entera porque no compila.
CLASES=()
for c in LiveState ObdParse Viaje; do
    [ -f "$SRC/$c.java" ] && CLASES+=("$SRC/$c.java")
done

echo "── compilando ${#CLASES[@]} clase(s) pura(s) + ${#TESTS[@]} test(s) ──"
javac -d "$SALIDA" "${CLASES[@]}" "${TESTS[@]}"

# ── Se ejecutan todos, y se cuentan ──────────────────────────────────────────
fallos=0
for t in "${TESTS[@]}"; do
    nombre="$(basename "$t" .java)"
    echo "── $nombre ──"
    if ! java -cp "$SALIDA" "$nombre"; then
        fallos=$((fallos + 1))
    fi
done

echo
if [ "$fallos" -ne 0 ]; then
    echo "RESULTADO: ❌ $fallos de ${#TESTS[@]} test(s) han fallado"
    exit 1
fi
echo "RESULTADO: ✅ los ${#TESTS[@]} tests JVM han pasado"
