# Cambio en el bridge para que el launcher lea datos (vía 1)

## Qué se ha decidido y por qué

El launcher necesita datos vivos del coche, pero **las alertas de Telegram y OBD
Telemetry tienen que seguir funcionando**. El bridge atiende clientes TCP de uno en uno
sobre el mismo enlace Bluetooth y el recolector de Termux ya es cliente de ese puerto, así
que el launcher no puede conectarse por su cuenta: o esperaría turno, o le robaría el
turno y rompería la telemetría.

La solución (vía 1): como la pantalla vive en el **mismo proceso** que el bridge, no abre
ninguna conexión: le pregunta **al bridge**, que es el dueño del enlace Bluetooth y decide
cuándo se puede hablar con el ELM.

## Qué cambia, exactamente

**Fichero: `app/src/com/cassiopeia/vgatebridge/BridgeService.java`. Nada más.**

1. **Dos flujos pasan de variables locales a campos** del `BridgeThread`: los flujos de
   entrada y salida del Bluetooth. Eran locales de `bridgeLoop`, y la pregunta directa
   necesita verlos.
2. **Se registra el puente vivo**: en el constructor de `BridgeThread`, una referencia
   estática al puente activo, para que la aplicación pueda localizarlo.
3. **Un candado y una marca**: `CANDADO` (un `ReentrantLock`) y `HAY_CLIENTE_TCP`. El
   servidor TCP marca `true` al aceptar un cliente y `false` al soltarlo.
4. **`BridgeService.preguntar(comando, timeoutMs)`**: método nuevo y público. Toma el
   candado, y **si hay un cliente TCP conectado devuelve `null` sin tocar el enlace**;
   si no lo hay, escribe el comando y lee la respuesta hasta el prompt del ELM, con
   límite de tiempo. Nunca espera indefinidamente y nunca interrumpe a un cliente.
5. **`enlaceOcupado()`**: método nuevo para que la pantalla sepa si el recolector está
   usando el puerto, sin intentarlo.

## Qué NO cambia (y es lo que importa)

- **El camino TCP del recolector es el mismo byte a byte.** El puerto 22000, el bucle de
  `accept`, el relay BT↔TCP y la re-inicialización del ELM (`ATZ`, `ATE0`, `ATL0`,
  `ATSP0`) siguen exactamente igual. El recolector no nota nada: entra, habla y sale como
  siempre.
- **El protocolo CAN y el perfil C-QUATRE no se tocan.** Ni el sniffer.
- **No se abre ningún enlace Bluetooth nuevo.** El Vgate admite uno y sigue siendo el
  mismo.
- **Nada cambia en el APK del recolector ni en los scripts de Cassiopeia.**

## La regla que hace que esto sea seguro

**Si hay un cliente conectado, la aplicación no habla con el ELM.** No se comparte la
conversación: se espera a que esté libre. Por eso no hacen falta tramas ni colas: no hay
dos conversaciones a la vez, nunca.

Y si una pregunta quedara a medias, el cliente siguiente limpia el adaptador al
conectarse, porque el bridge re-inicializa el ELM en cada entrada de cliente.

## Cómo se vuelve atrás

Dos formas, las dos triviales:

```bash
# 1. La etiqueta de git, en el repositorio
git checkout antes-via1-bridge -- app/src/com/cassiopeia/vgatebridge/BridgeService.java

# 2. La copia literal del fichero, con su md5 comprobado antes del cambio
cp ~/.hermes/backups/bridge/BridgeService.java.antes-via1 \
   ~/repos/vgate-bridge/app/src/com/cassiopeia/vgatebridge/BridgeService.java
```

## Qué está verificado y qué no

**Verificado:** compila y firma; los tests siguen pasando; el APK se construye.

**NO verificado todavía, y hay que hacerlo con el coche:**

- Que la pregunta directa devuelva datos reales del ELM.
- Que el recolector siga funcionando igual con el cambio dentro (esto es lo primero que
  hay que comprobar: conectar el recolector, ver que los datos llegan a Cassiopeia y que
  las alertas salen).
- Que cuando el recolector está conectado, la aplicación se aparte de verdad (debe
  devolver `null` y la pantalla seguir mostrando "sin datos", no datos a medias).

La respuesta cruda la interpreta `ObdParse`, que ya está probado en la JVM: aquí no se
añade ningún analizador nuevo.
