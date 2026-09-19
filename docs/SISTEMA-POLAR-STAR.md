# El sistema Polar Star — mapa completo

> Documento de conjunto. Explica **todas** las piezas, dónde vive cada una y cómo se
> hablan entre sí, para que alguien pueda entenderlo y seguir desarrollando.
>
> **Cómo leerlo:** donde dice *verificado* significa que lo he comprobado ejecutando o
> leyendo el código en esta sesión. Donde dice *por confirmar*, es lo que dicen los
> documentos del proyecto y todavía no he vuelto a comprobar con mis ojos. Esa distinción
> está a propósito: un mapa que confunde lo comprobado con lo recordado es peor que no
> tener mapa.

---

## 1. Qué es esto, en dos frases

**Polar Star** es el sistema de telemetría de un Citroën C4 Grand Picasso I (2006, 2.0 HDi
138 CV, motor DW10BTED4). Se compone de **dos mitades que se necesitan**: una **pantalla
en el salpicadero** (el launcher, que además muestra datos reales del coche) y una
**infraestructura en casa** que recoge esos datos, los guarda y avisa por Telegram.

La particularidad: **la fuente de datos es propia**. La velocidad sale del puerto OBD del
coche, no del GPS. El consumo y la autonomía salen del bus CAN de la unidad, no de una
estimación. Eso es lo que permite hacer cosas que un launcher de tienda no puede.

---

## 2. Las piezas, y dónde vive cada una

| Pieza | Dónde vive | Qué hace |
|---|---|---|
| **La tablet** | En el coche, en el salpicadero | Topway TS18 (Unisoc UIS8581A), Android 10, 1024×600. **Sin root.** Se enciende y apaga con el contacto. Su WiFi cae a menudo (defecto de la ROM) |
| **El adaptador Vgate** | En el puerto OBD del coche | vLinker MC por Bluetooth. Habla ELM327 |
| **La app `com.cassiopeia.vgatebridge`** | La tablet | El corazón: puente OBD, sniffer CAN, launcher, diagnóstico. Un solo APK, ~57 KB |
| **Termux** | La tablet | SSH, cron, GPS logger, el vigilante y las tareas de arranque |
| **Cassiopeia** | En casa | El hub: recoge, guarda en base de datos, analiza y avisa |
| **Tailscale** | Los dos extremos | La red privada que los une. La tablet es `polar-star` |
| **OBD Telemetry** | Cassiopeia (`~/repos/obd-telemetry`) | El proyecto de recogida y análisis de los datos del coche |
| **El emulador** | Cassiopeia | Banco de pruebas Android 10 para desarrollar la pantalla sin el coche |

---

## 3. El recorrido de un dato

Hay **dos caminos** que entran al sistema, y no se mezclan:

### Camino OBD (el motor, por el puerto de diagnóstico)

```
Motor ──> Vgate (Bluetooth) ──> BridgeService (dentro de la app)
                                      │
                                      ├── TCP :22000 ──> recolector (Termux/Cassiopeia)
                                      │                        └──> base de datos ──> alertas
                                      │
                                      └── Sondeo (mismo proceso) ──> LiveState ──> launcher
```

**Verificado:** el bridge se conecta por Bluetooth RFCOMM e inicializa el ELM327 con
`ATZ`, `ATE0`, `ATL0`, `ATSP0`. Expone un servidor TCP en el **puerto 22000** que atiende
clientes **de uno en uno**, reutilizando el mismo enlace Bluetooth, y re-inicializa el ELM
cada vez que entra un cliente.

**Verificado:** la app puede preguntarle directamente al bridge (`BridgeService.preguntar`)
sin abrir conexiones. **Regla que lo hace seguro: si hay un cliente conectado, la app no
toca el enlace y se espera.** Nunca hay dos conversaciones con el ELM a la vez.

### Camino CAN (el coche, por el decodificador de la unidad)

```
Bus CAN ──> decodificador de la ROM (TWUtil) ──> CanSnifferService
                    └──> ficheros CSV (censo, traza) ──> recolector ──> base de datos
```

**Verificado:** el sniffer lee por `TWUtil` y los canales están identificados a base de
provocar el evento y ver qué cambia: puertas `1281/58`, alumbrado `1281/56`, temperatura
`1281/54` + `1288/0`, consumo y autonomía `1281/51`.

**Pendiente y es importante:** el sniffer **todavía no escribe en `LiveState`**. Hoy la
pantalla no recibe nada por CAN. Es la mitad que falta del camino.

### Quién manda en cada dato

| Dato | Fuente primaria | Por qué |
|---|---|---|
| Velocidad, régimen, refrigerante, carga, voltaje | **OBD** | Son del motor y solo el puerto de diagnóstico los da |
| Nivel de combustible | **OBD** (`012F`) | El CAN de esta unidad no lo publica |
| Consumo, autonomía, temperatura exterior | **CAN** | Ya llegan bien por el decodificador; pedirlos por OBD gastaría turno del adaptador |
| Puertas, alumbrado | **CAN** | Son estados de la unidad, no del motor |

---

## 4. Dentro de la app (la tablet)

Todo esto vive en **un solo proceso**, y eso es una decisión de diseño: los servicios
escriben en un estado común y la pantalla lo lee sin intermediarios.

| Componente | Papel |
|---|---|
| `LauncherActivity` | La pantalla de inicio (**HOME**). Declarada así, es lo que se ve al arrancar la tablet |
| `BridgeService` | Dueño del enlace Bluetooth y del puerto 22000 |
| `CanSnifferService` | Lee el CAN de la ROM y escribe los CSV del censo |
| `Sondeo` | Pregunta al bridge y vuelca en `LiveState` |
| `LiveState` | **La fuente única de la verdad** del estado del coche, con sello de tiempo por dato |
| `InstrumentoView`, `BarraView`, `TestigoView` | Los instrumentos, dibujados a mano con `Canvas` |
| `NavegacionView` | Panel de navegación: maniobra leída de las notificaciones de Maps/Waze |
| `MusicaFranja` | Música en franja compacta, vía MediaSession |
| `CajonView` | Cajón de aplicaciones con los iconos reales |
| `DiagnosticoActivity` | Estado de cada dato: origen, edad y validez |
| `ViajeActivity` + `Viaje`/`Viajes` | Ordenador de viaje: actual, hoy y total |
| `MediaListener` | La llave del sistema para leer lo que publican otras apps (permiso de notificaciones) |
| `Diario` | El registro: logcat con etiqueta `PolarStar` **y** `diagnosticos.txt`, que sobrevive al apagón |
| `Pizarra` | Lo que la pantalla dice estar dibujando, para poder comprobarlo desde fuera |
| `Ajustes`, `Apps`, `MediaSesion`, `Navegacion`, `Paleta` | Configuración, aplicaciones, reproductor, navegación y color |

**La regla que sostiene todo:** un dato ausente se pinta como una raya, nunca como un
cero; y un dato viejo **caduca** y vuelve a ser desconocido. Enseñar un valor congelado
como si fuera actual es la única mentira que este sistema no se permite.

---

## 5. Termux (la tablet)

Termux es un Linux dentro de Android sin root. Aquí hace de fontanería:

- **`sshd`** en el puerto 8022, para entrar por Tailscale como `polar-star`.
- **`crond`**: ejecuta `polar_boot_extra.sh` **cada minuto**, que es el mecanismo que
  mantiene vivo todo lo demás.
- **`polar_boot_extra.sh`** hace tres cosas, y conviene sabérselas: arranca `crond` si no
  está; se instala a sí mismo en la tabla de cron si no está; y arranca `sshd` si no está.
  Pero **no** arranca los servicios de la app: de eso se encarga Android con la app
  declarada como pantalla de inicio.
- **El vigilante** *(por confirmar en su forma actual)*: comprueba que los servicios de la
  app siguen vivos.
- **El JobScheduler del sistema** apunta a `polar_job.sh` cada 15 minutos, que es la vía
  que tiene Android de despertar algo aunque Termux haya sido matado.

**El GPS no es un programa aparte**: el recolector lo pide por SSH con `termux-location`
(`-p passive`, `-p network`, `-p gps`). Verificado leyendo el recolector. Esto importa
porque aclara de dónde sale la posición: la pide el lado de Cassiopeia, no el launcher.

> ⚠️ **Trampa conocida:** Android mata Termux cuando le apetece. Ya pasó, dos días
> seguidos. Por eso el trabajo crítico no vive solo en Termux: el JobScheduler y el
> vigilante existen para eso.

---

## 6. Cassiopeia (casa)

Aquí es donde los datos se guardan y se piensan:

- **`~/.hermes/scripts/`**: los scripts del sistema. Entre ellos `obd_collector_cron.sh`,
  `obd_local_import.sh`, `polar_boot_extra.sh`, `polar_watchdog.sh` y
  `obd_vehicle_config.json` (los umbrales reales del coche: régimen rojo 4800, aviso de
  refrigerante 95, velocidad rápida 120, combustible bajo 15 %).
- **La base de datos** `~/.hermes/data/obd_telemetry.db` (SQLite): lecturas, lecturas CAN,
  sesiones, repostajes y códigos de avería.
- **La base de contexto** `~/.hermes/data/context/context.db`: los viajes.
- **La webapp** en el puerto 8765 y **Janus** en el 8770.
- **Los análisis**: resúmenes de viaje, consumo real (8,29 L/100 km medidos por
  repostajes, frente a los 4,2-5,7 que marca el cuadro del coche), detección de repostajes.
- **`~/repos/obd-telemetry`**: el proyecto de recogida y análisis. **Aquí vive el
  recolector, y no en `~/.hermes/scripts`.**

### El recolector, en detalle (verificado)

- **Dónde:** `~/repos/obd-telemetry/collector/`, y se ejecuta con el python del entorno de
  Hermes (`~/.hermes/hermes-agent/venv/bin/python3`).
- **Cómo se lanza:** los envoltorios de `~/.hermes/scripts/` (`obd_collector_cron.sh`,
  `obd_local_import.sh`) hacen un `exec` al python del venv con el script de verdad. Es
  decir: los cronjobs de Hermes llaman al envoltorio, y el envoltorio llama al recolector.
- **Cómo alcanza la tablet:** abre un **socket TCP** contra la dirección y el puerto que
  lee de la configuración (`obd.host` / `obd.port`), que apunta al puerto 22000 de la
  tablet por Tailscale. Es decir: **es un cliente TCP del bridge**, exactamente igual que
  si fuera una herramienta externa. No hay nada especial ni privilegiado en él.
- **Y además entra por SSH** (`ssh polar-star ...`) para pedir la posición con
  `termux-location`.
- **Lo que hay en esa carpeta:** `obd_collector.py` y `obd_local_collector.py` (los
  recolectores), `obd2_client.py` (el cliente del puerto OBD), `car_status.py` (el
  odómetro), `fuel_consumption.py` y `fuel_prices.py`, `merge_sessions.py`,
  `check_polar_star.py` (comprobación de salud) y `deploy_obd_v3.sh` (despliegue).

**Consecuencia para el desarrollo:** el recolector **no** es un componente de la app. Es un
cliente externo por TCP. Eso confirma que la decisión tomada (que la app no compita por el
puerto) es la correcta, y explica por qué la vía 1 va por dentro del proceso.

---

## 7. Tailscale y las alertas

**Tailscale** une la tablet con Cassiopeia y con los dos servidores (Orion, producción de
Biodevas; Andromeda, réplicas). Se usa **MagicDNS**, no direcciones IP: `polar-star`,
`orion-biodevas`, `andromeda`.

**Las alertas de Telegram** son cronjobs de Hermes que dependen del recolector:

| Tarea | Cada |
|---|---|
| Detectar llegada | 5 min |
| Resumen de viaje | 5 min |
| Importar datos locales | 5 min |
| Redeploy del recolector | 5 min |
| Detector de repostajes (CAN) | 10 min |

**Consecuencia directa:** cualquier cambio en el bridge o en el recolector puede romper
estas alertas. Por eso la decisión tomada es **no competir por la conexión**.

---

## 7 bis. Quién dispara qué (esto confunde a todo el mundo)

Hay tres mecanismos de temporización distintos y conviene no mezclarlos:

| Mecanismo | Dónde | Para qué |
|---|---|---|
| **Cronjobs de Hermes** | Cassiopeia | Recoger, importar, analizar y avisar por Telegram. Cada 5 o 10 minutos |
| **`crond` de Termux** | La tablet | Solo mantener viva la fontanería: `polar_boot_extra.sh` cada minuto |
| **JobScheduler de Android** | La tablet | Despertar `polar_job.sh` cada 15 minutos, aunque Android haya matado Termux |

**Quién es el dueño de qué, para no duplicar trabajo:**

- **Los datos del motor (OBD)**: el recolector de Cassiopeia. La app los pide también para
  la pantalla, pero **cediendo el turno** (vía 1). Una sola conexión, un solo dueño del
  enlace: el bridge.
- **Los viajes y las sesiones**: **Cassiopeia**, en su base de datos. El ordenador de viaje
  del launcher es **local e informativo**: cuenta lo que ve esa tablet y sirve para
  consultarlo en el coche, pero la cifra canónica es la de la base de datos. Si algún día
  discrepan, manda Cassiopeia.
- **La posición**: el recolector, por SSH con `termux-location`. El launcher **no usa GPS**
  para nada: su velocidad es la del motor.
- **Los umbrales del coche** (régimen rojo, aviso de refrigerante, combustible bajo...):
  la fuente canónica es `obd_vehicle_config.json` en Cassiopeia. El launcher los lee de su
  propio `launcher.json`, y **sus valores por defecto deben coincidir** con los de allí.

## 8. Cómo se desarrolla y cómo se verifica

```bash
make test            # tests JVM de las clases puras (sin Android, sin emulador)
make build           # compila y firma el APK
make verify          # test + build + 110+ comprobaciones del APK y la pantalla
make emulador        # emulador Android 10: crea, arranca, instala
make emulador-parar
```

**Tres niveles de verificación, y cada uno dice una cosa distinta:**

1. **Compila** → que el código es coherente. Nada más.
2. **Tests JVM** (`app/tests/`) → las cuentas puras: el estado del coche, el analizador de
   respuestas OBD y el ordenador de viaje. Los tests **se descubren**, no se listan: uno
   nuevo no puede quedarse sin ejecutar en silencio.
3. **Comportamiento en el emulador** → se inyectan estados conocidos y se compara **lo que
   la pantalla dibuja** (llamado *pizarra*) con lo esperado. Es la diferencia entre «dice
   que pinta» y «pinta».

**Nada de esto sustituye a probarlo con el coche.** El emulador no tiene adaptador
Bluetooth ni bus CAN: valida la interfaz y la lógica, y ahí se acaba.

**Lo que se toca en la tablet** se toca solo cuando se decide desplegar, y `~/.hermes` y
los repositorios son la fuente de verdad; lo que viaja a GitHub es escaparate.

---

## 9. Estado: qué está hecho y qué falta

**Hecho y verificado:**

- Launcher nativo (sin WebView) con cuadro, navegación, música, cajón de aplicaciones,
  diagnóstico y ordenador de viaje. Tema día/noche automático por el alumbrado.
- La app puede preguntar al bridge sin competir con el recolector (vía 1).
- Estado con sello de tiempo por dato, con tres estados que no se confunden: actual,
  caducado y sin dato.
- Diario de diagnóstico que sobrevive al apagón de la tablet.

**Falta:**

- **La conexión viva.** El sondeo existe pero nunca ha hablado con el adaptador: eso
  necesita el coche. Y el sniffer CAN todavía no escribe en `LiveState`.
- **El despliegue en la tablet.** La versión actual está construida y verificada, sin
  instalar.
- Pantallas de sistema (batería, Bluetooth, WiFi, almacenamiento), configuración visual,
  búsqueda en el cajón, unidades y formato horario.

---

## 9 bis. Cómo se entra, y cómo se monta desde cero

### Entrar

```bash
ssh polar-star                 # la tablet (alias de ~/.ssh/config, por Tailscale)
ssh orion-biodevas             # servidor de producción de Biodevas
ssh andromeda                  # réplicas
```

Dentro de la tablet, lo que se suele mirar:

| Qué | Dónde |
|---|---|
| Carpeta de trabajo de la app | `/sdcard/Android/data/com.cassiopeia.vgatebridge/files/` |
| Diario de la app | .../`diagnosticos.txt` (sobrevive al apagón) |
| Lo que la pantalla dibuja | .../`pintado.json` |
| Datos de prueba (solo emulador) | .../`test_state.json`, `test_media.json` |
| CSV del sniffer CAN | la misma carpeta (`can_census.csv`, `can_changes.csv`, `can_readings.csv`) |
| Termux | `/data/data/com.termux/files/home/` |

El logcat se mira con `adb logcat -s PolarStar` (por USB) o desde Termux, que en esta ROM
puede leer el log de otra aplicación.

### Montar desde cero

1. **Instalar la app** (`make build` y el APK de `app/build/`) **con la misma firma** que la
   instalada, o habrá que desinstalar y se perderá la MAC del coche.
2. **Conceder el acceso a notificaciones** (Ajustes → Notificaciones → Acceso a
   notificaciones). Sin él no hay música ni estado de navegación: la pantalla lo dice y
   ofrece el enlace, pero hay que darlo a mano.
3. **Fijar la app como pantalla de inicio** (Ajustes → Aplicación de inicio). **No
   desinstalar** el launcher de la ROM: es la salida de emergencia.
4. **Emparejar el Vgate** por Bluetooth, si se ha cambiado el adaptador.
5. **Desplegar Termux**: `polar_boot_extra.sh` (arranca crond, se pone en el cron y levanta
   sshd), la clave de Tailscale y el alias `polar-star`.
6. **Comprobar el estado**: abrir el diagnóstico (pulsación larga en el engranaje) y ver
   que OBD y CAN digan algo coherente con lo que hace el coche.
7. **Comprobar las alertas**: que el recolector llegue a Cassiopeia y que los cronjobs de
   Telegram sigan avisando.

## 10. Reglas que no se rompen

1. **El protocolo CAN y el perfil C-QUATRE no se tocan.** Es la única configuración que da
   consumo.
2. **La firma del APK debe ser la misma** o la actualización falla y hay que desinstalar
   (se perdería la MAC configurada del coche).
3. **El stub `android.tw.john.TWUtil` se compila pero no puede entrar en el APK**: taparía
   la clase real de la ROM y el CAN dejaría de leerse.
4. **No se abre un segundo enlace Bluetooth** al adaptador: solo admite uno.
5. **Un dato ausente es una raya, y un dato viejo caduca.** Nunca un cero inventado.
6. **Antes de tocar la tablet se avisa**, y los cambios se hacen primero en el repositorio.
7. **El launcher no es una web.** Es nativo y así se queda.

---

## 11. Glosario rápido

- **Vgate** — el adaptador Bluetooth que habla con el puerto OBD del coche.
- **ELM327** — el lenguaje de ese puerto. Los comandos empiezan por `01` (PIDs).
- **PID** — cada dato concreto del motor. `0D` velocidad, `0C` régimen, `05` refrigerante…
- **CAN** — el bus interno del coche. De ahí salen puertas, luces y consumo.
- **TWUtil** — la clase de la ROM china que da acceso a ese bus.
- **LiveState** — el estado compartido del coche dentro de la app.
- **Pizarra** — lo que la pantalla dice estar dibujando, para poder comprobarlo.
- **ReColector** — el programa que recoge los datos y los manda a Cassiopeia.
- **Cassiopeia** — el ordenador de casa, el hub del sistema.
