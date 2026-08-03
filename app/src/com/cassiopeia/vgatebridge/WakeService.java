package com.cassiopeia.vgatebridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * WakeService — arranque completo del stack en modo coche.
 *
 * Flujo (invocado por MainActivity en carLaunchMode):
 *  1. RUN_COMMAND -> polar_boot_extra.sh en Termux (sshd, crond, GPS, recolector).
 *  2. Abre TermuxActivity un instante: la ROM china deja Termux en estado
 *     "stopped" tras suspensión profunda y Android bloquea startService a
 *     apps stopped; abrir su actividad LAUNCHER la saca de ese estado y
 *     permite que RUN_COMMAND (paso 1) se entregue.
 *  3. Espera a que haya red (ConnectivityManager) ANTES de abrir Tailscale:
 *     si se abre sin cobertura, la VPN no conecta y queda en off.
 *  4. Abre Tailscale (su IPNService no es exportado; única vía es su
 *     MainActivity) y vuelve al launcher.
 *
 * Sin UI propia, notificación efímera (IMPORTANCE_LOW).
 */
public class WakeService extends Service {

    private static final String CHANNEL_ID = "vgate_wake";
    private static final int NOTIF_ID = 4201;

    private static final String TAILSCALE_PKG = "com.tailscale.ipn";
    private static final String TAILSCALE_ACTIVITY = "com.tailscale.ipn.MainActivity";
    private static final String TERMUX_PKG = "com.termux";
    private static final String TERMUX_ACTIVITY = "com.termux.app.TermuxActivity";

    // Tiempo que dejamos visible a Termux para sacarlo de "stopped"
    private static final long TERMUX_GRACE_MS = 1500;
    // Reintento de red: cada 10s, SIN límite — la cobertura del coche puede
    // tardar minutos en llegar; rendirse antes deja Tailscale abierta sin VPN.
    private static final long NET_RETRY_MS = 10000;
    // Tiempo que dejamos a Tailscale para conectar la VPN antes de volver
    private static final long TAILSCALE_GRACE_MS = 3000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean tailscaleLaunched = false;

    private final Runnable runCommandTask = new Runnable() {
        @Override
        public void run() {
            startTermuxServices();
        }
    };

    private final Runnable termuxWakeTask = new Runnable() {
        @Override
        public void run() {
            launchTermux();
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    // Termux ya tuvo su flash: volver al launcher y esperar red
                    goHome();
                    checkNetwork();
                }
            }, TERMUX_GRACE_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("VgateBridge")
                .setContentText("Reactivating services…")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setPriority(Notification.PRIORITY_LOW)
                .build();
        startForeground(NOTIF_ID, notification);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 1. RUN_COMMAND -> Termux (polar_boot_extra.sh)
        handler.postDelayed(runCommandTask, 1000);
        // 2. Despertar Termux (sacar de "stopped")
        handler.postDelayed(termuxWakeTask, 1500);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(runCommandTask);
        handler.removeCallbacks(termuxWakeTask);
        super.onDestroy();
    }

    /**
     * 3. Espera red SIN límite y, cuando haya, lanza Tailscale.
     *    El servicio sigue en foreground con notificación; puede esperar
     *    minutos sin que Android lo mate.
     */
    private void checkNetwork() {
        if (tailscaleLaunched) return;

        if (hasNetwork()) {
            tailscaleLaunched = true;
            launchTailscale();
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    goHome();
                    stopSelf();
                }
            }, TAILSCALE_GRACE_MS);
        } else {
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    checkNetwork();
                }
            }, NET_RETRY_MS);
        }
    }

    private boolean hasNetwork() {
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Lanza los servicios de Termux via RUN_COMMAND.
     * Requiere allow-external-apps=true en ~/.termux/termux.properties.
     * Si falla (Termux ausente o sin permiso), no rompe nada: el bridge sigue.
     */
    private void startTermuxServices() {
        try {
            Intent i = new Intent("com.termux.RUN_COMMAND");
            i.setClassName("com.termux", "com.termux.app.RunCommandService");
            i.putExtra("com.termux.RUN_COMMAND_PATH",
                    "/data/data/com.termux/files/home/polar_boot_extra.sh");
            i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
            startService(i);
        } catch (Exception e) {
            // Ignorar: no debe impedir el arranque del bridge
        }
    }

    /**
     * Abre Termux un instante para sacarlo del estado "stopped".
     */
    private void launchTermux() {
        try {
            Intent t = new Intent(Intent.ACTION_MAIN);
            t.setClassName(TERMUX_PKG, TERMUX_ACTIVITY);
            t.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(t);
        } catch (Exception e) {
            // Ignorar: si Termux no está, el crond no puede ayudar, pero no rompe
        }
    }

    /**
     * Abre Tailscale para conectar la VPN. Si no está instalada, no rompe nada.
     */
    private void launchTailscale() {
        try {
            Intent ts = new Intent(Intent.ACTION_MAIN);
            ts.setClassName(TAILSCALE_PKG, TAILSCALE_ACTIVITY);
            ts.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(ts);
        } catch (Exception e) {
            // Ignorar
        }
    }

    /** Vuelve al launcher del vehículo sin matar nada. */
    private void goHome() {
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(home);
        } catch (Exception e) {
            // Nada más que hacer
        }
    }

    private void createNotificationChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "VgateBridge wake", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Reactiva servicios Termux tras encendido");
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
