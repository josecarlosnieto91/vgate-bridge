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
57 KB.

**La interfaz es NATIVA** (vistas de Android y vistas propias dibujadas con `Canvas`)
desde la v5.4.0. Hasta la v5.3.x fue una página HTML dentro de un WebView. Se migró
por decisión del responsable del proyecto, y porque las vistas nativas permiten usar
los iconos **reales** de las aplicaciones y arrancar antes, que es lo que se espera de
un launcher de verdad.

## 2. El hardware y sus límites (no son negociables)

| Cosa | Dato |
|---|---|
| Tablet | Topway TS18, SoC Unisoc UIS8581A (8×Cortex-A55, gama baja), 1024×600 apaisada |
| Android | 10 (API 29), **sin root** (no hay ADB inalámbrico; solo USB) |
| Alimentación | Del coche: la tablet se apaga con el contacto. Muchas averías hay que probarlas con el motor en marcha |
| Red | **El WiFi cae con frecuencia** (defecto conocido de estas ROM, no es nuestro software) |
| Acceso | `ssh polar-star` (Tailscale) |
| Compilación | **A mano**: `build.sh` con `javac` + `d8` + `apksigner`. **No hay Gradle** |

Por no haber Gradle, tres reglas que hay que respetar o el build falla:

- **Solo se compilan los ficheros PLANOS en `src/com/cassiopeia/vgatebridge/`.** El
  `build.sh` usa `$SRC/*.java`, que no es recursivo: una clase en una subcarpeta no se
  compila, y el fallo aparece como «no encuentro la clase» desde otra.
- **No hay AndroidX ni bibliotecas de terceros.** Solo `android.*` del sistema: un
  `import androidx...` no compila. Eso incluye la interfaz: nada de RecyclerView ni de
  Compose; la rejilla de aplicaciones usa el `GridView` del sistema.
- **Nivel de lenguaje conservador**: sin funciones flecha ni `stream`.

## 3. Cómo está construido

```
LauncherActivity  (declarada como HOME en AndroidManifest.xml)
   ├── InstrumentoView   aro, escala CON NÚMEROS, zona roja, aguja y valor
   ├── BarraView         nivel de combustible y autonomía
   ├── TestigoView       avisos, que solo existen mientras están activos
   ├── MusicaView        carátula, título, artista, álbum y controles
   └── CajonView         el cajón con todas las aplicaciones instaladas
        ↑ leen
   LiveState  (el estado del coche: lo escriben los servicios, lo lee la pantalla)
        ↑ escriben
   CanSnifferService (CAN por TWUtil)   ·   MediaListener + MediaSession (música)
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

**Sobre el debate WebView o nativo** (medido en su momento, no opinado): el puente
Java→JS tardaba 0,52 ms por llamada y pintar la aguja 0,22 ms, margen de sobra para
20 Hz; el cuello de botella real era **el bus del coche**, no la pantalla. Donde el
WebView sí costaba era en el arranque en frío y en no poder usar iconos reales. La
migración a nativo fue decisión del responsable del proyecto, con esos números delante.

**Por qué la pantalla no usa Internet**: el WiFi de esta tablet falla a menudo y una
pantalla que dependiera de la red se rompería justo cuando falla. Las apps que lanza
(Maps, Waze, Spotify) sí tienen red; la restricción es solo sobre nuestra pantalla. No
hay ninguna clase que abra una conexión: la interfaz es local de principio a fin.

## 4. Qué está hecho y qué no

**Hecho y verificado:**

- Cuadro con instrumentos **dibujados a mano** (aro, escala **con números**, zona roja
  según los umbrales reales del coche, aguja que se mueve interpolada, no a saltos):
  velocidad como principal, con régimen y refrigerante alrededor.
- Combustible y autonomía en barra: el combustible baja en días, no merece aguja.
- Tira con consumo y temperatura exterior. Reloj y fecha en castellano.
- **Avisos solo cuando ocurren**: puertas, luces y los umbrales del coche
  (refrigerante ≥95, combustible ≤15 %, velocidad ≥120).
- Reproductor integrado leyendo de MediaSession: carátula, título, artista, álbum y
  controles grandes. Distingue «sin permiso» de «sin reproducción».
- **Cajón de aplicaciones** con los iconos REALES de cada app instalada, más favoritos.
- Accesos directos configurables por fichero (`launcher.json`), sin recompilar.
- Tema día/noche **automático por el alumbrado del coche**, no por la hora.
- Salida de emergencia a los ajustes de inicio de Android, para poder volver al
  launcher de la ROM. Y la pantalla no se apaga conduciendo.
- Entorno de pruebas: emulador Android 10 reproducible y prueba de correspondencia
  contra lo que la pantalla dibuja.

**Sin hacer (lo que continúa):**

- **Tareas 3 y 4 del plan: los datos EN VIVO.** Hoy la pantalla lee `LiveState`, pero
  nadie escribe ahí todavía desde el coche. **Hay una decisión tomada y una trampa ya
  explicada: leer el apartado «Cómo se conectarán los datos en vivo»** antes de tocar
  nada. Falta (a) esa conexión y (b) enganchar el CAN del sniffer a `LiveState`.
- **Despliegue en la tablet**: la v5.5.0 está construida y verificada, sin instalar.
- Pantallas de sistema (batería, Bluetooth, WiFi, almacenamiento) y configuración visual
  de `launcher.json`: búsqueda en el cajón, unidades y formato horario.
- **El mapa embebido**: descartado por ahora. Exigiría abrir la red o una caché de
  teselas propia (`osmdroid`), y las de OpenStreetMap no se pueden usar desde una
  app sin contratar un proveedor. Hoy es un botón que abre Maps o Waze.

## 5. Trampas que ya nos costaron tiempo (leer antes de tocar)

- **El stub `android.tw.john.TWUtil` se compila pero NO debe entrar en el DEX.**
  Si entra, tapa la clase real de la ROM y el sniffer CAN deja de funcionar. El
  `build.sh` solo le pasa a `d8` las clases de la app. `make verify` lo comprueba.
- **Solo se compilan los ficheros planos de `src/com/cassiopeia/vgatebridge/`** (ver
  punto 2): una clase en una subcarpeta no entra en el APK y el error aparece en otra.
- **Alumbrado encendido = noche.** Esta lógica se invirtió al migrar a nativo y el
  cuadro salió en claro de noche, que es justo lo que deslumbra. Hay una comprobación
  automática que vigila esa línea exacta.
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

## 5 bis. Cómo se conectarán los datos en vivo (decisión tomada)

**DECISIÓN DEL RESPONSABLE DEL PROYECTO: se sigue usando el Vgate Bridge tal y como
está, porque las alertas de Telegram y OBD Telemetry tienen que seguir funcionando.**
Eso manda sobre cualquier otra consideración.

**Consecuencia directa, y conviene tenerla clara:** el launcher **NO** abre su propia
conexión al ELM327. Y no por prudencia mal entendida, sino porque no puede:

- El bridge expone ELM327 en `127.0.0.1:22000` y atiende clientes **de uno en uno**,
  reutilizando el mismo enlace Bluetooth.
- El recolector de Termux ya es cliente de ese puerto. Si el launcher se conectara
  también, o se queda esperando turno indefinidamente (con el riesgo de bloquear la
  reconexión del recolector), o le roba el turno y **rompe la telemetría y las alertas**.
- Y una conexión Bluetooth paralela al adaptador no es una opción: el Vgate admite una.

**Las dos vías que sí respetan la decisión**, para elegir una el día que se pruebe con
el coche:

1. **Que el bridge sirva el estado a la app (recomendada).** Como el launcher vive en el
   MISMO proceso que el bridge, no hace falta socket: se le pide el dato directamente a
   `BridgeService`, y es él —que es el dueño del enlace Bluetooth— quien serializa el
   acceso. El recolector por TCP sigue funcionando igual porque el bridge es el mismo.
   ⚠️ **No es una línea de código trivial**: el relay TCP actual reenvía bytes sin
   entender de tramas, así que hay que darle estructura de petición/respuesta antes de
   dejar que dos caminos compartan el enlace. Hacerlo mal atasca el ELM y se lleva por
   delante la telemetría. Requiere copia previa del bridge, cambio documentado y
   verificación con el coche en marcha.
2. **Que el recolector publique lo que ya lee.** El recolector ya tiene los datos; si
   publicara el último estado en un fichero pequeño en la tablet, el launcher lo leería
   sin tocar el ELM en absoluto: contención cero. Se toca un script nuestro (versionado
   y verificable), no el bridge. A cambio, la frescura depende de la cadencia del
   recolector, que es más lenta que un cuadro de instrumentos.

**Lo que NO se hace:** conectar el launcher por su cuenta al puerto 22000, abrir un
segundo enlace Bluetooth, o cambiar el protocolo CAN o el perfil C-QUATRE.

## 6. Cómo se trabaja en esto

```bash
make                 # ayuda
make test            # tests JVM de las clases puras (LiveState, ObdParse)
make build           # compila y firma el APK
make verify          # test + build + 92 comprobaciones ← antes de commitear
make emulador        # emulador Android 10: crea/arranca/instala y comprueba que arranca
make emulador-parar  # lo apaga
```

**Verificación de comportamiento** — lo que distingue «compila» de «funciona». La
pantalla nativa no se puede interrogar como el DOM, y `uiautomator` además falla porque
estas pantallas nunca se quedan quietas. Así que **cada instrumento anota lo que acaba
de dibujar** al terminar su propio `onDraw`, y la Activity vuelca esa pizarra a
`pintado.json`. La prueba inyecta estados conocidos, espera y compara lo DIBUJADO:

```bash
cd ~/.hermes/verify_logs/launcher-emulador && python3 correspondencia-nativa.py
```

La diferencia con leer el estado del coche es la que importa: el estado dice lo que
**llegó**, la pizarra dice lo que se **pintó**. Si un instrumento dejara de refrescarse,
el estado seguiría diciendo que todo va bien y esta prueba lo caza.

**Cómo se inyectan datos de prueba** (para trabajar sin coche): se copia un JSON a la
carpeta de la app y la pantalla lo usa en vez del estado del coche. Los relee cada
150 ms, así que se puede cambiar con la pantalla abierta y ver el efecto al momento.

El fichero inyectado describe el estado **completo**: lo que no aparece queda
desconocido. Así se puede probar el caso que más importa —el dato que NO llega— que
debe verse como una raya, nunca como un cero.

```bash
adb push estado.json /sdcard/Android/data/com.cassiopeia.vgatebridge/files/test_state.json
adb push musica.json /sdcard/Android/data/com.cassiopeia.vgatebridge/files/test_media.json
```

**Antes de tocar la tablet**: comprobar que lo que hay en `~/repos/vgate-bridge` es
la fuente de verdad; el repositorio público es solo escaparate.

## 7. Dónde está cada cosa

- `app/src/com/cassiopeia/vgatebridge/LauncherActivity.java` — la pantalla y su refresco
- `app/src/com/cassiopeia/vgatebridge/InstrumentoView.java` — el instrumento
- `app/src/com/cassiopeia/vgatebridge/BarraView.java` — combustible y autonomía
- `app/src/com/cassiopeia/vgatebridge/TestigoView.java` — los avisos
- `app/src/com/cassiopeia/vgatebridge/MusicaView.java` — el reproductor
- `app/src/com/cassiopeia/vgatebridge/CajonView.java` — el cajón de aplicaciones
- `app/src/com/cassiopeia/vgatebridge/LiveState.java` — el estado vivo del coche
- `app/src/com/cassiopeia/vgatebridge/{Ajustes,Apps,MediaSesion,Paleta,Pizarra}.java`
  — configuración, aplicaciones, reproductor, colores e instrumentación
- `app/src/com/cassiopeia/vgatebridge/MediaListener.java` — llave para leer el reproductor
- `app/src/com/cassiopeia/vgatebridge/CanSnifferService.java` — el CAN y el censo
- `app/tools/verificar.sh` y `app/tools/emulador.sh` — verificación y entorno
- `app/tests/` — tests JVM de las clases puras
- Planes y contexto: `~/.hermes/plans/` y el skill `car-telematics` (con las
  referencias del censo CAN, los protocolos de la ROM y los launchers comerciales)
