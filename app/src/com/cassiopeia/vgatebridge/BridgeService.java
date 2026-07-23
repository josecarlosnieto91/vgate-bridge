package com.cassiopeia.vgatebridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

public class BridgeService extends Service {
    public static final int TCP_PORT = 22000;
    public static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final int NOTIF_ID = 22000;
    private static final String CHANNEL_ID = "vgate_bridge";
    private static final String PREFS_NAME = "VgatePrefs";
    private static final String KEY_MAC = "vgate_mac";
    private static final String KEY_NAME = "vgate_name";

    public static final String ACTION_START = "com.cassiopeia.vgatebridge.START";
    public static final String ACTION_STOP = "com.cassiopeia.vgatebridge.STOP";
    public static final String ACTION_STATUS = "com.cassiopeia.vgatebridge.STATUS";
    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_LOG = "log";
    public static final String STATUS_CONNECTED = "connected";
    public static final String STATUS_DISCONNECTED = "disconnected";
    public static final String STATUS_ERROR = "error";
    public static final String STATUS_WAITING = "waiting";

    private BridgeThread bridge;
    private NotificationManager notifManager;
    private PowerManager.WakeLock wakeLock;
    private PrintWriter logFile;
    private String currentStatus = STATUS_DISCONNECTED;
    private String btMac = null;

    @Override
    public void onCreate() {
        super.onCreate();
        notifManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel();
        openLogFile();

        // Acquire wake lock
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VgateBridge:Wakelock");
        wakeLock.acquire(10 * 60 * 1000L); // 10 min timeout, will reacquire if active

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        btMac = prefs.getString(KEY_MAC, null);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;

        String action = intent.getAction();

        if (ACTION_START.equals(action)) {
            String mac = intent.getStringExtra("mac");
            if (mac != null) {
                btMac = mac;
            }
            if (btMac == null) {
                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                btMac = prefs.getString(KEY_MAC, null);
            }
            startBridge();
        } else if (ACTION_STOP.equals(action)) {
            stopBridge();
            stopForeground(true);
            stopSelf();
        }

        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopBridge();
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        closeLogFile();
        super.onDestroy();
    }

    private void startBridge() {
        if (btMac == null) {
            broadcastStatus(STATUS_ERROR, "No hay Vgate configurado");
            return;
        }
        if (bridge != null && bridge.isAlive()) {
            broadcastStatus(STATUS_CONNECTED, "Puente ya activo");
            return;
        }

        showNotification("Iniciando...", STATUS_WAITING);
        broadcastStatus(STATUS_WAITING, "Iniciando puente...");

        bridge = new BridgeThread(btMac);
        bridge.start();
    }

    private void stopBridge() {
        if (bridge != null) {
            bridge.stopBridge();
            bridge = null;
        }
        currentStatus = STATUS_DISCONNECTED;
        broadcastStatus(STATUS_DISCONNECTED, "Puente detenido");
        cancelNotification();
    }

    public void log(final String msg) {
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        String line = "[" + ts + "] " + msg;
        System.out.println("[VgateBridge] " + msg);

        // File log
        if (logFile != null) {
            logFile.println(line);
            logFile.flush();
        }

        // Broadcast to activity
        Intent intent = new Intent(ACTION_STATUS);
        intent.putExtra(EXTRA_LOG, msg);
        sendBroadcast(intent);
    }

    private void broadcastStatus(String status, String info) {
        currentStatus = status;
        Intent intent = new Intent(ACTION_STATUS);
        intent.putExtra(EXTRA_STATUS, status);
        if (info != null) intent.putExtra(EXTRA_LOG, info);
        sendBroadcast(intent);

        if (STATUS_CONNECTED.equals(status)) {
            showNotification("Puerto " + TCP_PORT, STATUS_CONNECTED);
        } else if (STATUS_ERROR.equals(status)) {
            showNotification(info, STATUS_ERROR);
        } else if (STATUS_WAITING.equals(status)) {
            showNotification(info, STATUS_WAITING);
        } else {
            cancelNotification();
        }
    }

    private void showNotification(String text, String status) {
        String title;
        int icon;
        if (STATUS_CONNECTED.equals(status)) {
            title = "VgateBridge — ACTIVO";
            icon = android.R.drawable.presence_online;
        } else if (STATUS_ERROR.equals(status)) {
            title = "VgateBridge — ERROR";
            icon = android.R.drawable.presence_busy;
        } else if (STATUS_WAITING.equals(status)) {
            title = "VgateBridge — Conectando...";
            icon = android.R.drawable.presence_away;
        } else {
            title = "VgateBridge";
            icon = android.R.drawable.presence_offline;
        }

        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(icon)
            .setOngoing(true)
            .setPriority(Notification.PRIORITY_LOW);

        Notification notification = builder.build();
        startForeground(NOTIF_ID, notification);
    }

    private void cancelNotification() {
        stopForeground(true);
        notifManager.cancel(NOTIF_ID);
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID, "VgateBridge",
            NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Estado del puente OBD2 Bluetooth");
        channel.setShowBadge(false);
        notifManager.createNotificationChannel(channel);
    }

    private void openLogFile() {
        try {
            File dir = new File(getExternalFilesDir(null), "logs");
            dir.mkdirs();
            String date = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            File f = new File(dir, "vgatebridge-" + date + ".log");
            logFile = new PrintWriter(new FileOutputStream(f, true));
            log("--- VgateBridge v3 iniciado ---");
        } catch (Exception e) {
            System.err.println("No se pudo abrir log file: " + e.getMessage());
        }
    }

    private void closeLogFile() {
        if (logFile != null) {
            logFile.close();
            logFile = null;
        }
    }

    // ====== BRIDGE THREAD ======

    class BridgeThread extends Thread {
        private final String mac;
        private volatile boolean running = true;
        private BluetoothSocket btSocket;
        private ServerSocket serverSocket;
        private Socket tcpSocket;

        BridgeThread(String mac) {
            this.mac = mac;
        }

        void stopBridge() {
            running = false;
            closeSilently(btSocket);
            closeSilently(tcpSocket);
            closeSilently(serverSocket);
        }

        @Override
        public void run() {
            while (running) {
                if (!connectBluetooth()) {
                    log("Reintentando en 10 segundos...");
                    broadcastStatus(STATUS_WAITING, "Reintentando en 10s...");
                    sleep(10000);
                    continue;
                }

                if (!startTcpServer()) {
                    closeSilently(btSocket);
                    log("Reintentando en 5 segundos...");
                    sleep(5000);
                    continue;
                }

                // Bridge loop
                bridgeLoop();

                // Cleanup for reconnect
                closeSilently(btSocket);
                closeSilently(tcpSocket);
                closeSilently(serverSocket);
                btSocket = null;
                tcpSocket = null;
                serverSocket = null;

                log("Puente cerrado, esperando 10s para reconectar...");
                broadcastStatus(STATUS_WAITING, "Reconectando en 10s...");
                sleep(10000);
            }

            log("Puente finalizado");
            broadcastStatus(STATUS_DISCONNECTED, "Puente finalizado");
        }

        private boolean connectBluetooth() {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) {
                log("ERROR: No hay Bluetooth adapter");
                broadcastStatus(STATUS_ERROR, "Sin Bluetooth adapter");
                sleep(5000);
                return false;
            }

            // Retry loop: wait for BT to become available
            for (int attempt = 1; attempt <= 5; attempt++) {
                if (adapter.isEnabled()) {
                    log("Bluetooth disponible (intento " + attempt + ")");
                    break;
                }
                log("Bluetooth no activo, intento " + attempt + "/5");
                try { adapter.enable(); } catch (Exception e) {
                    log("No se pudo activar BT: " + e.getMessage());
                }
                broadcastStatus(STATUS_WAITING, "Esperando BT (" + attempt + "/5)...");
                sleep(3000);
            }

            if (!adapter.isEnabled()) {
                log("ERROR: Bluetooth no disponible tras 5 intentos");
                broadcastStatus(STATUS_ERROR, "BT no disponible. Activalo manualmente");
                return false;
            }

            try {
                BluetoothDevice device = adapter.getRemoteDevice(mac);
                String devName = device.getName();
                log("Conectando a " + (devName != null ? devName : mac));

                try {
                    btSocket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                } catch (Exception e) {
                    log("RFCOMM UUID fallo, probando channel 1");
                    java.lang.reflect.Method m = device.getClass().getMethod(
                        "createRfcommSocket", int.class);
                    btSocket = (BluetoothSocket) m.invoke(device, 1);
                }

                adapter.cancelDiscovery();
                btSocket.connect();
                log("Bluetooth conectado!");
                return true;

            } catch (Exception e) {
                log("Error BT: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                return false;
            }
        }

        private boolean startTcpServer() {
            try {
                serverSocket = new ServerSocket(TCP_PORT);
                log("Servidor TCP en puerto " + TCP_PORT + " - esperando cliente...");
                broadcastStatus(STATUS_WAITING, "Esperando cliente TCP en :" + TCP_PORT);

                serverSocket.setSoTimeout(300000); // 5 min timeout
                tcpSocket = serverSocket.accept();
                tcpSocket.setSoTimeout(0);
                log("Cliente TCP conectado: " + tcpSocket.getInetAddress().getHostAddress());
                return true;

            } catch (Exception e) {
                log("Error TCP server: " + e.getMessage());
                return false;
            }
        }

        private void bridgeLoop() {
            try {
                InputStream btIn = btSocket.getInputStream();
                OutputStream btOut = btSocket.getOutputStream();
                InputStream tcpIn = tcpSocket.getInputStream();
                OutputStream tcpOut = tcpSocket.getOutputStream();

                // Initialize ELM327
                btOut.write("ATZ\r\n".getBytes()); btOut.flush(); sleep(1500);
                btOut.write("ATE0\r\n".getBytes()); btOut.flush(); sleep(500);
                btOut.write("ATL0\r\n".getBytes()); btOut.flush(); sleep(500);
                btOut.write("ATSP0\r\n".getBytes()); btOut.flush(); sleep(500);

                log("ELM327 inicializado");
                broadcastStatus(STATUS_CONNECTED, "Puente activo en :" + TCP_PORT);

                final InputStream fBtIn = btIn;
                final OutputStream fBtOut = btOut;
                final InputStream fTcpIn = tcpIn;
                final OutputStream fTcpOut = tcpOut;

                Thread btToTcp = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        byte[] buf = new byte[4096];
                        try {
                            while (running) {
                                if (fBtIn.available() > 0) {
                                    int n = fBtIn.read(buf);
                                    if (n > 0) {
                                        fTcpOut.write(buf, 0, n);
                                        fTcpOut.flush();
                                    } else if (n == -1) break;
                                } else {
                                    sleep(50);
                                }
                            }
                        } catch (Exception e) {
                            if (running) log("BT->TCP error: " + e.getMessage());
                        }
                    }
                });

                Thread tcpToBt = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        byte[] buf = new byte[4096];
                        try {
                            while (running) {
                                if (fTcpIn.available() > 0) {
                                    int n = fTcpIn.read(buf);
                                    if (n > 0) {
                                        fBtOut.write(buf, 0, n);
                                        fBtOut.flush();
                                    } else if (n == -1) break;
                                } else {
                                    sleep(50);
                                }
                            }
                        } catch (Exception e) {
                            if (running) log("TCP->BT error: " + e.getMessage());
                        }
                    }
                });

                btToTcp.start();
                tcpToBt.start();

                // Monitor connection health
                while (running && btSocket.isConnected() && tcpSocket.isConnected()) {
                    // Test if BT socket is alive
                    try {
                        btIn.available();
                    } catch (Exception e) {
                        log("BT socket cerrado: " + e.getMessage());
                        break;
                    }
                    sleep(2000);
                }

                log("Puente desconectado");
                btToTcp.interrupt();
                tcpToBt.interrupt();

            } catch (Exception e) {
                log("Error en puente: " + e.getMessage());
            }
        }

        private void sleep(int ms) {
            try { Thread.sleep(ms); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void closeSilently(Object obj) {
        try {
            if (obj instanceof BluetoothSocket) ((BluetoothSocket) obj).close();
            else if (obj instanceof Socket) ((Socket) obj).close();
            else if (obj instanceof ServerSocket) ((ServerSocket) obj).close();
        } catch (Exception e) {}
    }
}
