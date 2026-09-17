#!/data/data/com.termux/files/usr/bin/sh
# vehicle tablet — arranque de servicios Termux (idempotente, robusto a timing)
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

# 5. (Eliminado 2026-09-17) Watchdog de 30 s — era REDUNDANTE: solo cubría
#    sshd y el GPS logger, que este mismo script revive cada minuto, y costaba
#    `ps aux` + greps cada 30 s (~17.000 spawns/día) además de impedir el
#    reposo del dispositivo. La recuperación pasa de ≤30 s a ≤60 s: irrelevante.
#    El fichero ~/polar_watchdog.sh ya no se usa (se borra en el despliegue).

# 5b. Recolector OBD local — fuente primaria de datos SIN Internet.
#     (Añadido 2026-08-03: se moría tras cada arranque y NADIE lo relanzaba
#     → la webapp se quedaba sin datos. El crond lo mantiene vivo ahora.
#     ⚠️ 2026-08-09: se perdió al desplegar desde el repo anonimizado — el
#     repo no tenía esta sección. Restaurada en ambos sitios.)
start_local_collector() {
    if ! pgrep -f "obd_local_collector.py" > /dev/null 2>&1; then
        if [ -f ~/obd_local_collector.py ]; then
            nohup python3 ~/obd_local_collector.py >/dev/null 2>&1 &
        fi
    fi
}
start_local_collector
sleep 2
start_local_collector

# 5c. Re-armar el trabajo del JobScheduler (OPT 2026-09-17).
#     Un trabajo `--persisted true` NO sobrevive aquí al ciclo de corriente:
#     tras el apagón del 17/09, `termux-job-scheduler -p` respondía «No jobs
#     found». Así que se vuelve a programar en cada arranque.
#     ⚠️ Solo en los primeros 2 minutos de uptime: si no, se pagaría un IPC
#     al JobScheduler cada minuto (justo lo que estamos quitando).
up=$(cut -d. -f1 /proc/uptime 2>/dev/null || echo 999)
if [ "${up:-999}" -lt 120 ] && command -v termux-job-scheduler > /dev/null 2>&1; then
    case "$(termux-job-scheduler -p 2>/dev/null)" in
        *"Job 7:"*) : ;;   # ya está programado
        *) termux-job-scheduler -s "$HOME/polar_job.sh" --job-id 7 \
               --period-ms 900000 --persisted true \
               --battery-not-low false --network any > /dev/null 2>&1 ;;
    esac
fi

# 6. Tailscale — refuerzo del WakeService.
#    El WakeService (VgateBridge v9.2) espera red y abre Tailscale cuando hay
#    conectividad. Este refuerzo cubre el caso de que la ROM mate el proceso
#    de la app durante la espera: si hay red pero la VPN no responde, relanza
#    Tailscale (con anti-spam de 3 min para no abrir la UI cada minuto).
#
#    ⚠️ FIX 2026-08-06: Tailscale solo se abre UNA VEZ por arranque del coche.
#    El flag ~/.tailscale_launched_once se crea la primera vez que se lanza y
#    solo se limpia en boot completo (termux-boot.sh). Así, las pérdidas de
#    conexión posteriores (cambio de red, torre móvil) NO reabren la app y no
#    roban el foco al usuario (Maps/Spotify quedan al frente).
#    ⚠️ OPT 2026-09-17: el chequeo profundo (dos pings seguidos) se hacía CADA
#    minuto y despertaba la radio 1.440 veces al día. Ahora como mucho cada
#    5 min.
#    ⚠️ El gate es SOLO temporal a propósito: desde Termux no se puede saber si
#    la app de Tailscale está viva (`pgrep -f tailscaled` no la ve — Android no
#    deja ver procesos de otras apps), así que ese atajo daba siempre «no
#    está» y no gateaba nada.
TS_FLAG=~/.tailscale_retry_ts
TS_ONCE=~/.tailscale_launched_once
TS_CHECK=~/.tailscale_last_check
TS_PEER=100.64.0.1   # server — si responde, la VPN Tailscale está up
TS_INTERNET=1.1.1.1      # referencia de conectividad general

tailscale_connected() {
    ping -c 1 -W 2 $TS_PEER >/dev/null 2>&1
}

has_internet() {
    ping -c 1 -W 3 $TS_INTERNET >/dev/null 2>&1
}

_ts_now=$(date +%s)
_ts_last=0
[ -f "$TS_CHECK" ] && _ts_last=$(cat "$TS_CHECK" 2>/dev/null || echo 0)
if [ $((_ts_now - _ts_last)) -ge 300 ]; then
    echo "$_ts_now" > "$TS_CHECK"
    if tailscale_connected; then
        # VPN OK: limpiar flag
        rm -f "$TS_FLAG"
    elif has_internet; then
        if [ ! -f "$TS_ONCE" ]; then
            # Primera vez en este arranque: abrir Tailscale y marcar.
            # A partir de aquí, aunque la VPN se caiga, NO se reabre.
            echo "$(date +%s)" > "$TS_ONCE"
            now=$(date +%s)
            last=0
            [ -f "$TS_FLAG" ] && last=$(cat "$TS_FLAG" 2>/dev/null || echo 0)
            if [ $((now - last)) -ge 180 ]; then
                echo "$now" > "$TS_FLAG"
                am start -n com.tailscale.ipn/.MainActivity >/dev/null 2>&1
                sleep 4
                # Volver a la app anterior (no abrir el launcher): pedir a
                # MainActivity que haga moveTaskToBack con EXTRA_GO_BACK.
                am start -n com.cassiopeia.vgatebridge/.MainActivity \
                    --ez go_back true >/dev/null 2>&1
            fi
        fi
    fi
fi
