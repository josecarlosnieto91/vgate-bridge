# Polar Star — Auto-start y puente OBD2

## APK VgateBridge (v2)

**Ruta:** `/tmp/vgate-bridge/build/vgate-bridge-v2.apk` (16KB)
**Propósito:** Puente Bluetooth SPP → TCP. Conecta al Vgate vLinker MC (`04:25:E8:5A:E4:D4`) y expone un servidor TCP en puerto **22000**.

### Permisos del APK
- BLUETOOTH, BLUETOOTH_ADMIN — para RFCOMM
- INTERNET — para el servidor TCP
- RECEIVE_BOOT_COMPLETED — auto-arranque
- ACCESS_FINE_LOCATION — necesario en Android 10+ para BT scanning
- WAKE_LOCK — mantener la tablet despierta

### Auto-arranque al boot (Android 10+)
- `BootReceiver.java` escucha `BOOT_COMPLETED` y `LOCKED_BOOT_COMPLETED`
- Abre la app con `auto_start=true`
- La app espera 5s (para que BT se inicie) y lanza el puente automáticamente

### Requisito
La app debe abrirse **al menos una vez manualmente** después de instalar para que Android registre el BootReceiver.

---

## Auto-arranque con Termux:Boot

### 1. Instalar Termux:Boot desde F-Droid
```bash
# En Polar Star, abrir F-Droid e instalar "Termux:Boot"
```

### 2. Configurar script de boot
```bash
mkdir -p ~/.termux/boot/
cp termux-boot.sh ~/.termux/boot/start-services
chmod +x ~/.termux/boot/start-services
```

### 3. El script hace:
- `termux-wake-lock` — evita que Android mate Termux
- `sshd` — servidor SSH para acceso remoto
- `am start` — lanza VgateBridge con auto-arranque
- GPS logger (si existe)

### 4. Tailscale auto-arranque
En Ajustes → Apps → Tailscale → "Iniciar al arrancar" (ON)
O desde la app Tailscale: Settings → "Run on boot"

---

## Flujo completo al arrancar el coche

```
Arranque del coche → corriente a la tablet
    ↓
Android boot (30-60s)
    ↓
Tailscale auto-arranca → conecta a Tailnet
Termux:Boot ejecuta start-services
    ↓
sshd → acceso SSH listo (puerto 8022)
am start → VgateBridge abre, espera 5s, conecta BT, abre TCP:22000
    ↓
Cassiopeia ya puede:
  - SSH a Polar Star
  - Conectarse al puente TCP:22000
  - Leer datos OBD2 en tiempo real
```

## Conexión desde Cassiopeia

```python
import socket
s = socket.socket()
s.connect(("100.100.19.98", 22000))

# Send AT commands
s.send(b"ATRV\r\n")     # read voltage
print(s.recv(1024))

s.send(b"010C\r\n")     # read RPM
print(s.recv(1024))

s.close()
```

O usando la librería obd:
```python
import obd
connection = obd.OBD(portstr="tcp://100.100.19.98:22000", fast=False)
rpm = connection.query(obd.commands.RPM)
print(rpm.value)
```
