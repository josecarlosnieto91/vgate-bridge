#!/data/data/com.termux/files/usr/bin/sh
# vehicle tablet — auto-start al arrancar el coche (Termux:Boot)
# Delegación: servicios Termux idempotentes en polar_boot_extra.sh
# VgateBridge se auto-arranca via su propio BootReceiver (BOOT_COMPLETED/POWER_CONNECTED)

termux-wake-lock

sleep 15

# Nuevo arranque del coche: permitir que Tailscale se abra una vez más
# (polar_boot_extra.sh crea ~/.tailscale_launched_once y no lo reabre hasta
# el próximo boot completo — evita flashes por pérdidas de conexión).
rm -f ~/.tailscale_launched_once

# Servicios Termux: sshd, GPS logger, watchdog, tailscale
if [ -f ~/polar_boot_extra.sh ]; then
    sh ~/polar_boot_extra.sh
fi
