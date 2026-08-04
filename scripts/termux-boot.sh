#!/data/data/com.termux/files/usr/bin/sh
# vehicle tablet — auto-start al arrancar el coche (Termux:Boot)
# Delegación: servicios Termux idempotentes en polar_boot_extra.sh
# VgateBridge se auto-arranca via su propio BootReceiver (BOOT_COMPLETED/POWER_CONNECTED)

termux-wake-lock

sleep 15

# Servicios Termux: sshd, GPS logger, watchdog, tailscale
if [ -f ~/polar_boot_extra.sh ]; then
    sh ~/polar_boot_extra.sh
fi
