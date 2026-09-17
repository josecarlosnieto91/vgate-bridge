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
    // ── Descubrimiento (temporal, 2026-09-17) ────────────────────────────
    // El sniffer solo interpretaba 1281/51; el resto del caudal suscrito se
    // tiraba a la basura. Esto deja constancia de QUÉ llega por cada
    // (canal, dato) para mapear el resto del coche (temperatura exterior,
    // puertas, luces…). Coste: una búsqueda en mapa por mensaje; se puede
    // quitar cuando el mapa esté hecho.
    private static final String CENSUS_NAME = "can_census.csv";
    private static final String TEMP_NAME = "can_temp.csv";
    private static final long CENSUS_DUMP_MS = 300_000L;   // volcado cada 5 min
    private final java.util.HashMap<String, Censo> censo = new java.util.HashMap<>();
    private long lastCensusDump;
    private String ultimaTempHex = "";

    /** Una fila del censo: cuántos mensajes, de qué tamaño y cómo son. */
    private static final class Censo {
        long n;
        int largo;
        String hex = "";
        int minByte = Integer.MAX_VALUE;
        int maxByte = Integer.MIN_VALUE;
        byte[] ultimo;
    }

    private TWUtil tw;
    private PrintWriter csv;
    /** Cadencia/duplicados: lógica pura en CanWriteGate (probada aparte). */
    private final CanWriteGate gate = new CanWriteGate();

    private final Handler handler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            // 0. Censo: deja constancia de TODO lo que llega (no toca la
            //    recogida de producción, que sigue igual debajo).
            anotaCenso(msg);

            // 1. Temperatura exterior (ID 54 del canal 1281): se guarda aparte
            //    para poder decodificarla con datos reales sin tocar el CSV de
            //    producción ni el esquema de la BD.
            if (msg.what == 1281 && msg.arg1 == 54 && msg.obj instanceof byte[]) {
                anotaTemperatura((byte[]) msg.obj);
                return;
            }

            if (msg.what != 1281 || msg.arg1 != 51) return;
            Object o = msg.obj;
            if (!(o instanceof byte[])) return;
            byte[] d = (byte[]) o;
            if (d.length < 6) return;

            int consRaw = ((d[0] & 0xFF) << 8) | (d[1] & 0xFF);
            int range = ((d[2] & 0xFF) << 8) | (d[3] & 0xFF);
            int odom = ((d[4] & 0xFF) << 8) | (d[5] & 0xFF);

            long now = System.currentTimeMillis();
            float l100 = consRaw == 0xFFFF ? -1f : consRaw / 10.0f;

            // En parado el consumo medio es N/D pero el rango llega: se escribe
            // igualmente (más lento) para no perder el salto de un repostaje.
            if (!gate.shouldWrite(consRaw, range, now)) return;

            if (csv != null) {
                String ts = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(new Date());
                // Locale.US obligatorio: %.1f con locale español escribe "4,4"
                // (coma decimal) y rompe el CSV para el parser Python.
                csv.printf(Locale.US, "%s,%.1f,%d,%d%n", ts, l100, range, odom);
                csv.flush();
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

    /** Anota un mensaje en el censo. Nunca debe afectar a la producción. */
    private void anotaCenso(Message msg) {
        try {
            if (!(msg.obj instanceof byte[])) return;
            byte[] d = (byte[]) msg.obj;
            String clave = msg.what + "/" + msg.arg1;
            Censo c = censo.get(clave);
            if (c == null) {
                c = new Censo();
                censo.put(clave, c);
            }
            c.n++;
            c.largo = d.length;
            if (c.ultimo == null || !java.util.Arrays.equals(c.ultimo, d)) {
                c.ultimo = d.clone();
                StringBuilder sb = new StringBuilder();
                for (byte b : d) sb.append(String.format(Locale.US, "%02X", b));
                c.hex = sb.toString();
            }
            if (d.length > 0) {
                int v = d[0] & 0xFF;
                if (v < c.minByte) c.minByte = v;
                if (v > c.maxByte) c.maxByte = v;
            }
            long now = System.currentTimeMillis();
            if (lastCensusDump == 0 || now - lastCensusDump >= CENSUS_DUMP_MS) {
                volcarCenso(now);
            }
        } catch (Exception e) {
            // Silencio a propósito: el censo es auxiliar, la recogida es lo crítico.
        }
    }

    /** Foto del censo (se reescribe entera: son pocas claves y así no crece). */
    private void volcarCenso(long now) {
        lastCensusDump = now;
        try {
            File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (dir == null) return;
            if (!dir.exists()) dir.mkdirs();
            PrintWriter out = new PrintWriter(new FileWriter(new File(dir, CENSUS_NAME), false), true);
            out.println("what,arg1,n,bytes,hex,byte0_min,byte0_max");
            for (java.util.Map.Entry<String, Censo> e : censo.entrySet()) {
                String[] k = e.getKey().split("/");
                Censo c = e.getValue();
                out.printf(Locale.US, "%s,%s,%d,%d,%s,%d,%d%n",
                        k[0], k[1], c.n, c.largo, c.hex,
                        c.minByte == Integer.MAX_VALUE ? -1 : c.minByte,
                        c.maxByte == Integer.MIN_VALUE ? -1 : c.maxByte);
            }
            out.close();
        } catch (Exception e) {
            Log.e(TAG, "volcado del censo falló", e);
        }
    }

    /**
     * Temperatura exterior (1281/54). Se guarda el hex en crudo y solo cuando
     * cambia: con eso se decodifica (escala y offset) comparando con la
     * temperatura real. No toca el CSV de producción ni el esquema de la BD.
     */
    private void anotaTemperatura(byte[] d) {
        try {
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format(Locale.US, "%02X", b));
            String hex = sb.toString();
            if (hex.equals(ultimaTempHex)) return;
            ultimaTempHex = hex;
            File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (dir == null) return;
            File f = new File(dir, TEMP_NAME);
            boolean cabecera = !f.exists() || f.length() == 0;
            PrintWriter out = new PrintWriter(new FileWriter(f, true), true);
            if (cabecera) out.println("ts,hex");
            String ts = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(new Date());
            out.printf(Locale.US, "%s,%s%n", ts, hex);
            out.close();
        } catch (Exception e) {
            Log.e(TAG, "no se pudo anotar la temperatura", e);
        }
    }

    private void openCsv() {
        try {
            // Android 10: /sdcard/Download exige permiso runtime (EACCES en
            // servicio background). getExternalFilesDir() NO requiere permiso
            // y Termux (con storage) puede leerlo → /sdcard/Android/data/
            // com.cassiopeia.vgatebridge/files/Download/can_readings.csv
            File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (dir == null) {
                dir = getFilesDir(); // fallback: privado (Termux no lo lee)
            }
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
