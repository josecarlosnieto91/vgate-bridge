# Punto de entrada único de VgateBridge.
#
# El APK se construye a mano (build.sh + aapt/d8/apksigner), sin Gradle. Este
# Makefile solo ordena lo que ya existe, para que "verificar" sea un comando y no
# una receta que hay que recordar cada vez.
#
#   make            → ayuda
#   make verify     → tests JVM + build + comprobaciones del APK

SHELL := /bin/bash
APP   := $(CURDIR)/app

.PHONY: help test build verify emulador ver-limpiar

help:
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
		| awk 'BEGIN{FS=":.*?## "}{printf "  %-10s %s\n", $$1, $$2}'

test:  ## Tests JVM de las clases puras (sin Android, sin emulador)
	bash $(APP)/tests/run.sh

build:  ## Compila y firma el APK
	cd $(APP) && bash build.sh

verify: test build  ## Tests + build + comprobaciones del APK (esto antes de commitear)
	bash $(APP)/tools/verificar.sh

emulador:  ## Emulador Android 10: crea/arranca/instala y comprueba que arranca
	bash $(APP)/tools/emulador.sh probar

emulador-parar:  ## Apaga el emulador
	bash $(APP)/tools/emulador.sh parar
