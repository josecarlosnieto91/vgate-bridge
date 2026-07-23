package com.cassiopeia.vgatebridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) ||
            Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(intent.getAction())) {

            // Start the foreground service directly (not the activity)
            Intent serviceIntent = new Intent(context, BridgeService.class);
            serviceIntent.setAction(BridgeService.ACTION_START);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
        }
    }
}
