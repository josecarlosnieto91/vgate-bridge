# VgateBridge

Puente Bluetooth SPP → TCP para adaptadores OBD2 Vgate vLinker.

App Android que se conecta vía Bluetooth RFCOMM al Vgate y expone un servidor TCP local, permitiendo que herramientas externas (python-OBD, scripts, dashboards) lean datos del vehículo sin necesidad de root ni acceso directo al Bluetooth del dispositivo.

## Arquitectura

```
Vgate vLinker MC  ──BT RFCOMM──  Android (VgateBridge app)
                                        │
                                    TCP:22000
                                        │
                                   Cliente OBD2
                              (python-obd, scripts, etc.)
```

## Modelos compatibles

| Modelo | Bluetooth | Notas |
|--------|:---------:|-------|
| Vgate vLinker MC | SPP 3.0 | Probado. MAC típica `04:25:E8:*` |
| Vgate vLinker MC+ | SPP + BLE | Compatible, usa SPP |
| Vgate vLinker MC-Android | SPP 3.0 | = MC, mismo chipset |
| Cualquier ELM327 Bluetooth | SPP | Debe soportar UUID `00001101-0000-1000-8000-00805F9B34FB` |

> ⚠️ No funciona con adaptadores BLE-only que no exponen SPP RFCOMM.

## Requisitos de software

- **Android SDK** (para compilar): build-tools ≥ 34.0.0, platform android-29
- **Java JDK** ≥ 17
- **Dispositivo Android** con Bluetooth y Android ≥ 6.0 (API 23)

Si solo quieres instalar el APK, descarga la última versión precompilada desde [Releases](https://github.com/josecarlosnieto91/vgate-bridge/releases).

## Compilación

```bash
# Requisitos
export ANDROID_SDK=$HOME/android-sdk
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
export PLATFORM=$ANDROID_SDK/platforms/android-29
export BUILD_TOOLS=$ANDROID_SDK/build-tools/34.0.0

# Compilar
cd app/
javac -d build/classes \
    -bootclasspath $PLATFORM/android.jar \
    -source 8 -target 8 \
    src/com/cassiopeia/vgatebridge/*.java

# DEX (Dalvik Executable)
$BUILD_TOOLS/d8 --release --output build/ \
    build/classes/com/cassiopeia/vgatebridge/*.class

# Empaquetar APK
$BUILD_TOOLS/aapt package -f \
    -M AndroidManifest.xml \
    -I $PLATFORM/android.jar \
    -F build/vgate-bridge-unaligned.apk

# Añadir DEX
cd build && $BUILD_TOOLS/aapt add vgate-bridge-unaligned.apk classes.dex

# Firmar
$BUILD_TOOLS/apksigner sign --ks debug.keystore \
    --ks-pass pass:android --ks-key-alias debug \
    vgate-bridge-unaligned.apk

cp vgate-bridge-unaligned.apk vgate-bridge.apk
```

O usa el script `build.sh` incluido.

## Instalación

### Manual (recomendado)

1. Transfiere el APK al dispositivo Android
2. Ábrelo desde un gestor de archivos
3. Concede permiso para instalar aplicaciones de orígenes desconocidos
4. Abre la app al menos una vez para que Android registre el BootReceiver

### Desde ADB

```bash
adb install -r vgate-bridge.apk
```

### Vía SSH (Termux)

```bash
scp vgate-bridge.apk user@device:~
ssh user@device "cp vgate-bridge.apk /storage/emulated/0/Download/"
# Después abrir manualmente desde Descargas en el dispositivo
```

## Uso

1. **Abre la app** VgateBridge
2. **Concede permisos** de Bluetooth y Ubicación (necesario en Android 10+ para escanear BT)
3. **Selecciona tu Vgate** de la lista de dispositivos Bluetooth emparejados
4. Pulsa **INICIAR PUENTE**

La app arranca un servicio en primer plano con notificación persistente. El servidor TCP escucha en el puerto **22000**.

### Desde un cliente externo

```python
import socket

s = socket.socket()
s.connect(("IP_DEL_DISPOSITIVO", 22000))

# Comandos AT para ELM327
s.send(b"ATRV\r\n")        # Tensión batería
print(s.recv(4096))

s.send(b"010C\r\n")        # RPM
print(s.recv(4096))

s.close()
```

### Con python-OBD

```python
import obd
connection = obd.OBD(portstr="tcp://IP_DEL_DISPOSITIVO:22000", fast=False)
rpm = connection.query(obd.commands.RPM)
print(rpm.value)
```

## Características

- ✅ Servicio en **primer plano** — no lo mata Android al minimizar
- ✅ **Reconexión automática** — si el Vgate se desconecta, reintenta cada 10s
- ✅ **Selector de dispositivo** — elige el Vgate de una lista de emparejados
- ✅ **Notificación de estado** — icono verde/rojo/naranja en barra de estado
- ✅ **Auto-arranque al boot** — arranque automático al encender el vehículo
- ✅ **Log a fichero** — guarda registros en `Android/data/.../files/logs/`

## Auto-arranque al encender el coche

Para que la app arranque automáticamente al recibir corriente, instala **Termux:Boot** desde F-Droid y configura:

```bash
mkdir -p ~/.termux/boot/
cat > ~/.termux/boot/start-services << 'EOF'
#!/data/data/com.termux/files/usr/bin/sh
termux-wake-lock
sleep 15
sshd
sleep 10
am start -n com.cassiopeia.vgatebridge/.MainActivity --ez auto_start true 2>/dev/null
EOF
chmod +x ~/.termux/boot/start-services
```

## Ejecución como servicio

La app se ejecuta como **Foreground Service** de Android, no necesita configuración adicional de systemd. Simplemente:

1. Abre la app
2. Selecciona el dispositivo
3. Pulsa INICIAR

La notificación persistente indica que el servicio está activo. Para detenerlo, abre la app y pulsa DETENER.

## Estructura del proyecto

```
vgate-bridge/
├── app/
│   ├── AndroidManifest.xml
│   ├── src/com/cassiopeia/vgatebridge/
│   │   ├── MainActivity.java      # Interfaz de usuario
│   │   ├── BridgeService.java      # Servicio foreground + lógica del puente
│   │   └── BootReceiver.java       # Receptor de arranque
│   └── build.sh                    # Script de compilación
├── README.md
└── LICENSE
```

## Licencia

MIT
