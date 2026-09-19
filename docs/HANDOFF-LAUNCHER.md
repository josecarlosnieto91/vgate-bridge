# El launcher de Polar Star — qué es y cómo seguir

> Documento de traspaso. Si acabas de llegar a esto, léelo entero antes de tocar
> nada: hay decisiones tomadas por motivos que no se ven en el código, y errores
> que ya costaron tiempo y no conviene repetir.

## 1. Qué es esto

**Polar Star** es el nombre del sistema de telemetría de un Citroën C4 Grand Picasso
I (2006, 2.0 HDi 138 CV). Dentro de ese sistema, **el launcher** es la pantalla de
inicio de una tablet Android montada en el salpicadero: sustituye al launcher que
trae la ROM china —que viene cargado de software de relleno: 26 paquetes de las
marcas TW y dofun— por uno propio que hace dos cosas que ningún launcher comercial
puede hacer en esta unidad:

1. **Muestra datos REALES del coche**, leídos del bus CAN y del puerto OBD.
   Los launchers de tienda sacan la velocidad del GPS porque no son aplicación de
   sistema y no hablan con el decodificador. Aquí sí, porque la lectura la hace
   nuestra propia app.
2. **No depende de Internet.** Todo lo que muestra viene del coche o del propio APK.

Vive en el repositorio `~/repos/vgate-bridge`, dentro de la misma app
(`com.cassiopeia.vgatebridge`) que ya hacía de puente OBD. Es un APK pequeño: unos
46 KB.

## 2. El hardware y sus límites (no son negociables)

| Cosa | Dato |
|---|---|
| Tablet | Topway TS18, SoC Unisoc UIS8581A (8×Cortex-A55, gama baja), 1024×600 apaisada |
| Android | 10 (API 29), **sin root** (no hay ADB inalámbrico; solo USB) |
| WebView del sistema | **Chromium 74** |
| Alimentación | Del coche: la tablet se apaga con el contacto. Muchas averías hay que probarlas con el motor en marcha |
| Red | **El WiFi cae con frecuencia** (defecto conocido de estas ROM, no es nuestro software) |
| Acceso | `ssh polar-star` (Tailscale). El usuario de Telegram que dirige esto es JC |

**Chromium 74 implica:** nada de `gap` en contenedores flex (llegó en el 84), nada
de `aspect-ratio` (88), nada de flechas de función ni plantillas de texto. Los `gap`
de grid sí funcionan (desde el 57). Para crear SVG hay que usar `createElementNS`.

## 3. Cómo está construido

```
LauncherActivity  (declarada como HOME en AndroidManifest.xml)
   └── WebView, sin red, cargando un fichero LOCAL del APK:
        assets/launcher/index.html + launcher.css + launcher.js
             ↕  puente JavaScript (nombre "Android" en el WebView)
        WebBridge.java  → estado del coche, lista de apps, música, tema, abrir apps
             ↕
   LiveState.java  (estado del proceso: lo escriben los servicios, lo lee la pantalla)
             ↑                                    ↑
   CanSnifferService (CAN por TWUtil)      MediaListener + MediaSession (música)
```

**De dónde sale cada dato:**

- **CAN, a través del decodificador de la ROM** (`TWUtil`, dentro de
  `CanSnifferService`): consumo, autonomía, temperatura exterior, apertura de
  puertas y estado del alumbrado. **Estos datos ya están en el proceso**: la pantalla
  los tiene sin intermediarios. Identificados provocando el evento a mano y viendo
  qué cambia (ver `references/can-census.md` del skill `car-telematics`).
- **OBD, por el puente TCP** (`127.0.0.1:22000`, protocolo ELM327): velocidad real
  del ECU, régimen, temperatura del motor y el resto de PIDs. Es la vía que usa el
  recolector de Termux; el sondeo interno de la pantalla **todavía no existe**.
- **Música**: `MediaSessionManager`, con `MediaListener` como llave (ver punto 5).
- **Reloj**: local. Sin red.

**Por qué un WebView y no vistas nativas** — medido, no supuesto: el puente
Java→JS tarda 0,52 ms por llamada y pintar la aguja 0,22 ms, o sea margen de sobra
para una aguja a 20 Hz (unos 4 ms de CPU por segundo). El cuello de botella real es
**el bus del coche**: cada consulta al ELM327 tarda decenas de milisegundos, así que
la velocidad no puede llegar más rápido porque llegue antes la pantalla. Donde el
WebView sí cuesta es en el **arranque en frío**: 414 ms medidos en emulador
(hardware de escritorio), probablemente 1-2 s en la tablet. Decisión pendiente:
reescribir en nativo **solo los instrumentos** cuando el diseño se congele.

**Por qué la pantalla no tiene red** (`setBlockNetworkLoads(true)`): porque el WiFi
de esta tablet falla a menudo y una pantalla que dependa de Internet se rompería
justo cuando falla la red. Las apps que lanza (Maps, Waze, Spotify) sí tienen red;
la restricción es solo sobre nuestra pantalla.

## 4. Qué está hecho y qué no

**Hecho y verificado:**

- Cuadro con cuatro instrumentos dibujados desde la escala de cada magnitud (arcos
  SVG generados en JavaScript, con zona roja según los umbrales reales del coche):
  régimen, velocidad (el principal), combustible y temperatura del motor.
- Barra superior con reloj local y música (título, artista y controles).
- Tira de datos en texto: consumo, autonomía, temperatura exterior.
- Testigos de puertas y alumbrado.
- **Cajón de aplicaciones**: botón de rejilla que lista todas las apps instaladas,
  en mosaico, con la inicial como ficha (no se cargan iconos de terceros).
- Fila de accesos directos configurable, y salida de emergencia a los ajustes de
  inicio de Android para poder volver al launcher de la ROM.
- Tema día/noche **automático por el alumbrado del coche**, no por la hora.
- Configuración por fichero (`launcher.json`): apps y tema, sin recompilar.
- Entorno de pruebas: emulador Android 10 reproducible y prueba de correspondencia
  que lee el DOM real del WebView.

**Sin hacer (lo que continúa):**

- **Tareas 3 y 4 del plan: los datos EN VIVO.** Hoy las agujas se mueven con datos
  inyectados a mano. Falta (a) un sondeo OBD interno que comparta el enlace ELM con
  el recolector mediante un mutex —⚠️ es la parte más delicada, ver punto 6— y
  (b) enganchar el CAN del sniffer a `LiveState`.
- **Despliegue en la tablet**: la v5.3.0 está construida y verificada, sin instalar.
- Decisiones de diseño pendientes, propuestas tras consultar a un modelo externo y
  filtrarlas: combustible a barra en vez de arco, testigos solo cuando ocurren,
  avisos con icono sobre los umbrales, pantalla de viaje, indicador de eficiencia.
- **El mapa embebido**: descartado por ahora. Exigiría abrir la red o una caché de
  teselas propia (`osmdroid`), y las de OpenStreetMap no se pueden usar desde una
  app sin contratar un proveedor. Hoy es un botón que abre Maps o Waze.

## 5. Trampas que ya nos costaron tiempo (leer antes de tocar)

- **El stub `android.tw.john.TWUtil` se compila pero NO debe entrar en el DEX.**
  Si entra, tapa la clase real de la ROM y el sniffer CAN deja de funcionar. El
  `build.sh` solo le pasa a `d8` las clases de la app. `make verify` lo comprueba.
- **`gap` en flex no funciona en Chromium 74.** Engaña, porque los de grid sí
  funcionan y la pantalla parece casi bien. Comprobado en el emulador, y ahora es
  una comprobación automática que analiza regla por regla (no por líneas vecinas:
  eso daba falsos positivos).
- **Todo lo que haya en `assets/` viaja dentro del APK**, incluidas copias de
  seguridad. Un `.bak` se coló una vez; `build.sh` ahora aborta si los encuentra.
- **La firma tiene que ser la misma** que la de la versión instalada, o el update
  falla y hay que desinstalar (se perdería la MAC configurada del coche).
- **El protocolo del decodificador (perfil 21, `C-QUATRE`) no se toca.** Es la única
  configuración que funciona; perder el dato de consumo no compensa ninguna
  curiosidad.
- **No lanzar repetidamente la app del puente ni hacer `force-stop` a lo bruto**:
  una vez provocó un bucle de arranque. Y **no desinstalar** el launcher de la ROM.
- **Etiquetas heredadas que mienten:** en la base de datos, `can_readings.odometer_km`
  NO es un odómetro (es un valor constante del decodificador); el odómetro real lo
  calcula `car_status.get_odometer()` con una referencia del cuadro más los viajes.
  Y los datos CAN `1281/52` y `53` NO son contadores de viaje: son constantes.
- **Cuidado con el instrumento de medida.** Cuatro veces en una tarde el fallo
  estaba en la comprobación, no en el código: un patrón de búsqueda demasiado
  estricto, contar backticks de comentarios como sintaxis moderna, una versión
  fijada a mano que caducó, y un `grep` de líneas vecinas. Cuando una verificación
  falla, comprobar **primero** que no miente.

## 6. Cómo se trabaja en esto

```bash
make                 # ayuda
make test            # tests JVM de las clases puras (LiveState, ObdParse)
make build           # compila y firma el APK
make verify          # test + build + comprobaciones del APK y la pantalla ← antes de commitear
make emulador        # emulador Android 10: crea/arranca/instala y comprueba que arranca
make emulador-parar  # lo apaga
```

**Verificación de comportamiento** (esto es lo que distingue "compila" de
"funciona"): `~/.hermes/verify_logs/launcher-emulador/correspondencia.py` inyecta
estados conocidos en la app, abre la pantalla en el emulador y **lee el DOM real del
WebView** por el protocolo de depuración de Chrome, comparando campo a campo lo
inyectado con lo pintado. Requiere el emulador arrancado.

**Cómo se inyectan datos de prueba** (para trabajar sin coche): se copia un JSON a
la carpeta de la app y el puente lo devuelve tal cual en vez del estado real.

```bash
adb push estado.json /sdcard/Android/data/com.cassiopeia.vgatebridge/files/test_state.json
adb push musica.json /sdcard/Android/data/com.cassiopeia.vgatebridge/files/test_media.json
```

**Antes de tocar la tablet**: comprobar que lo que hay en `~/repos/vgate-bridge` es
la fuente de verdad; el repositorio público es solo escaparate.

## 7. Dónde está cada cosa

- `app/src/com/cassiopeia/vgatebridge/LauncherActivity.java` — la pantalla
- `app/src/com/cassiopeia/vgatebridge/WebBridge.java` — el puente con el JavaScript
- `app/src/com/cassiopeia/vgatebridge/LiveState.java` — el estado vivo del coche
- `app/src/com/cassiopeia/vgatebridge/MediaListener.java` — llave para leer el reproductor
- `app/src/com/cassiopeia/vgatebridge/CanSnifferService.java` — el CAN y el censo
- `app/assets/launcher/` — la interfaz (HTML, CSS y JavaScript)
- `app/tools/verificar.sh` y `app/tools/emulador.sh` — verificación y entorno
- `app/tests/` — tests JVM de las clases puras
- Planes y contexto: `~/.hermes/plans/` y el skill `car-telematics` (con las
  referencias del censo CAN, los protocolos de la ROM y los launchers comerciales)
