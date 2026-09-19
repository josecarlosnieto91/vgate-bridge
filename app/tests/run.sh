#!/usr/bin/env bash
# Tests JVM del APK del launcher. NO necesitan Android, ni emulador, ni tablet:
# las clases que se prueban son puras a propósito.
#
# Uso: bash app/tests/run.sh      (desde cualquier sitio)
set -eu
AQUI="$(cd "$(dirname "$0")" && pwd)"
SRC="$AQUI/../src/com/cassiopeia/vgatebridge"
SALIDA="$(mktemp -d)"
trap 'rm -rf "$SALIDA"' EXIT

echo "── compilando clases puras + tests ──"
javac -d "$SALIDA" \
    "$SRC/LiveState.java" \
    "$SRC/ObdParse.java" \
    "$AQUI/LiveStateTest.java" \
    "$AQUI/ObdParseTest.java"

echo "── LiveState ──"
java -cp "$SALIDA" LiveStateTest

echo "── ObdParse ──"
java -cp "$SALIDA" ObdParseTest

echo
echo "todos los tests JVM: OK"
