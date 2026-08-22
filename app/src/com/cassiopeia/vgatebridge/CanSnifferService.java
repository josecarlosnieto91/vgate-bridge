package com.cassiopeia.vgatebridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import android.tw.john.TWUtil;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * CanSnifferService — Polar Star (T-Win/Unisoc)
 *
 * Lee el decodificador CAN de la head unit vía TWUtil (API del framework,
 * misma que usa la app com.tw.car/.C4LActivity) y vuelca los datos a
 * /sdcard/Download/can_readings.csv para que el recolector Termux los
 * incorpore al sync con Cassiopeia.
 *
 * Mensaje 1281 del TWUtil: arg1 = ID del dato, obj = byte[].
 *   ID 51: [consumo L/100km ×10][rango km][odómetro km] — FFFF = N/D
 *   ID 54: temperatura exterior °C
 *
 * CSV: ts ISO,consumption_l100,range_km,odometer_km
 * Solo escribe cuando el consumo es válido (no FFFF) para no llenar el
 * archivo de N/D en parado.
 *
 * v4.8 (2026-08-22): integración del consumo CAN real.
 */
public class CanSnifferService extends Service {
    private static final String TAG = "CanSniffer";
    private static final String CHANNEL_ID = "vgate_can";
    private static final int NOTIF_ID = 22001;
    private static final short[] CHANNELS = {266, 1281, 1288, 267, 513, 524, 523, -24804};

    private static final String CSV_NAME = "can_readings.csv";
    private static final long MIN_INTERVAL_MS = 2000; // no escribir más de 1/2s

    private TWUtil tw;
    private PrintWriter csv;
    private long lastWrite = 0;
    private int lastConsRaw = -1;

    private final Handler handler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            if (msg.what != 1281 || msg.arg1 != 51) return;
            Object o = msg.obj;
            if (!(o instanceof byte[])) return;
            byte[] d = (byte[]) o;
            if (d.length < 6) return;

            int consRaw = ((d[0] & 0xFF) << 8) | (d[1] & 0xFF);
            int range = ((d[2] & 0xFF) << 8) | (d[3] & 0xFF);
            int odom = ((d[4] & 0xFF) << 8) | (d[5] & 0xFF);

            long now = System.currentTimeMillis();
            // Evitar duplicados: mismo valor en la misma ventana
            if (consRaw == lastConsRaw && (now - lastWrite) < MIN_INTERVAL_MS) return;
            lastConsRaw = consRaw;

            float l100 = consRaw == 0xFFFF ? -1f : consRaw / 10.0f;
            if (l100 < 0) return; // N/D en parado — no escribir

            if (csv != null) {
                String ts = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(new Date());
                csv.printf("%s,%.1f,%d,%d%n", ts, l100, range, odom);
                csv.flush();
                lastWrite = now;
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundCompat();
        openCsv();
        startCan();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopCan();
        closeCsv();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void startCan() {
        try {
            tw = new TWUtil();
            int rc = tw.open(CHANNELS);
            Log.i(TAG, "open() = " + rc);
            if (rc == 0) {
                tw.addHandler("CanSniffer", handler);
                tw.start();
                Log.i(TAG, "CAN sniffer activo");
            } else {
                tw = null;
                Log.e(TAG, "open() falló — TWUtil sin permisos");
            }
        } catch (Throwable t) {
            Log.e(TAG, "error arrancando TWUtil", t);
            tw = null;
        }
    }

    private void stopCan() {
        try {
            if (tw != null) {
                tw.removeHandler("CanSniffer");
                tw.stop();
                tw.close();
            }
        } catch (Throwable ignored) {}
        tw = null;
    }

    private void openCsv() {
        try {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, CSV_NAME);
            boolean header = !f.exists() || f.length() == 0;
            csv = new PrintWriter(new FileWriter(f, true));
            if (header) {
                csv.println("ts,consumption_l100,range_km,odometer_km");
                csv.flush();
            }
            Log.i(TAG, "CSV: " + f.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "no se pudo abrir CSV", e);
            csv = null;
        }
    }

    private void closeCsv() {
        try {
            if (csv != null) { csv.flush(); csv.close(); }
        } catch (Exception ignored) {}
        csv = null;
    }

    private void startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "CAN", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(ch);
            Notification n = new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("CAN")
                    .setContentText("Leyendo consumo del decodificador")
                    .setSmallIcon(android.R.drawable.ic_menu_info_details)
                    .build();
            startForeground(NOTIF_ID, n);
        }
    }
}
