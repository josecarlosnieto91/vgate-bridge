#!/data/data/com.termux/files/usr/bin/sh
# Polar Star — arranque de servicios Termux (idempotente, robusto a timing)
# Invocado por: termux-boot.sh (boot completo), VgateBridge (POWER_CONNECTED/USER_PRESENT)
# y CROND (cada minuto, mecanismo principal de auto-arranque en ciclos de corriente).
# NO toca VgateBridge (la app se gestiona sola via BootReceiver).

# 1. Cron de auto-mantenimiento: ejecuta este script cada minuto.
#    Es la capa fiable: en ciclos de corriente la tablet NO reinicia y Android
#    bloquea startService() desde la app en background (el RUN_COMMAND de la app
#    falla en silencio en Android 8+). El cron no depende de nada: si crond
#    está vivo, cada minuto levanta lo que falte.
if ! pgrep -x crond > /dev/null 2>&1; then
    crond
fi
crontab -l 2>/dev/null | grep -q polar_boot_extra || \
    echo '* * * * * sh ~/polar_boot_extra.sh >/dev/null 2>&1' | crontab -

# 2. Esperar a que Termux esté listo: en ciclos de corriente Termux acaba de
#    despertar y python3 puede tardar en estar disponible. Sin esta espera,
#    el nohup python3 del logger falla en silencio.
i=0
while [ $i -lt 30 ]; do
    command -v python3 >/dev/null 2>&1 && break
    sleep 1
    i=$((i+1))
done

# 3. SSH
if ! pgrep -x sshd > /dev/null 2>&1; then
    sshd
fi

# 4. GPS logger — doble intento: el primero puede fallar si el entorno aún no está listo
start_logger() {
    if ! pgrep -f "polar_gps_logger.py" > /dev/null 2>&1; then
        if [ -f ~/polar_gps_logger.py ]; then
            nohup python3 ~/polar_gps_logger.py >/dev/null 2>&1 &
        fi
    fi
}
start_logger
sleep 2
start_logger

# 5. Watchdog — igual
start_watchdog() {
    if ! pgrep -f "polar_watchdog.sh" > /dev/null 2>&1; then
        if [ -f ~/polar_watchdog.sh ]; then
            nohup sh ~/polar_watchdog.sh >/dev/null 2>&1 &
        fi
    fi
}
start_watchdog
sleep 2
start_watchdog

# 6. Tailscale (si el binario existe)
if command -v tailscale > /dev/null 2>&1; then
    tailscale up 2>/dev/null
fi
