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

        // Cable de corriente conectado (vuelta de tensión tras apagado corto)
        } else if (Intent.ACTION_POWER_CONNECTED.equals(action)) {
            startBridge(context);
            startTermuxServices(context);

        // Pantalla desbloqueada (tablet despierta tras suspensión)
        } else if (Intent.ACTION_USER_PRESENT.equals(action)) {
            startBridge(context);
            startTermuxServices(context);
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
