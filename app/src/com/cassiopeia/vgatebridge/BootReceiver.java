package com.cassiopeia.vgatebridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;

        // Arranque completo del sistema
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
            Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {
            startBridge(context);
            startTermuxServices(context);
            openMainActivity(context);

        // Cable de corriente conectado (vuelta de tensión tras apagado corto)
        } else if (Intent.ACTION_POWER_CONNECTED.equals(action)) {
            startBridge(context);
            startTermuxServices(context);
            openMainActivity(context);

        // Pantalla desbloqueada (tablet despierta tras suspensión)
        } else if (Intent.ACTION_USER_PRESENT.equals(action)) {
            startBridge(context);
            startTermuxServices(context);
            openMainActivity(context);
        }
    }

    /**
     * Abre MainActivity para que carLaunchMode ejecute el stack completo en
     * foreground. FIX 2026-09-03: antes solo se arrancaban servicios; sin la
     * app en foreground, Android 10 bloquea que WakeService abra Termux
     * ("Background activity start blocked") → crond/recolector/GPS nunca
     * arrancaban al encender el coche; solo al abrir la app manualmente.
     * Los receivers de BOOT/POWER sí pueden iniciar actividades. MainActivity
     * es singleTask y con car_mode=true ejecuta carLaunchMode (idempotente:
     * si la tarea ya existe, onNewIntent; si ya está corriendo, no duplica).
     */
    private void openMainActivity(Context context) {
        try {
            Intent i = new Intent(context, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
        } catch (Exception e) {
            // No debe impedir el arranque del bridge
        }
    }

    private void startBridge(Context context) {
        Intent serviceIntent = new Intent(context, BridgeService.class);
        serviceIntent.setAction(BridgeService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent);
        } else {
            context.startService(serviceIntent);
        }
    }

    /**
     * Lanza los servicios de Termux (sshd, GPS logger, watchdog, recolector
     * OBD) via WakeService → RUN_COMMAND.
     *
     * Android 8+ bloquea startService() de RunCommandService desde un receiver
     * en background (IllegalStateException). WakeService se lanza con
     * startForegroundService (igual que BridgeService, que sí funciona) y,
     * ya en primer plano, ejecuta el RUN_COMMAND legalmente.
     */
    private void startTermuxServices(Context context) {
        try {
            Intent serviceIntent = new Intent(context, WakeService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
        } catch (Exception e) {
            // Ignorar: no debe impedir el arranque del bridge
        }
    }
}
