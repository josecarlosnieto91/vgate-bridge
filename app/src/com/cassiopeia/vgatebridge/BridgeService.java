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
import android.util.Log;
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

    // ── Petición directa desde la propia aplicación (vía 1) ──────────────────
    //
    // POR QUÉ ESTO Y NO UN SOCKET MÁS: el bridge atiende clientes TCP de uno en uno
    // sobre el MISMO enlace Bluetooth. El recolector de Termux ya es cliente, así que
    // el launcher no puede conectarse por su cuenta: o esperaría turno, o le robaría
    // el turno y rompería la telemetría y las alertas de Telegram. En cambio, la
    // pantalla vive en ESTE MISMO PROCESO: puede preguntarle al bridge directamente.
    //
    // LA REGLA, QUE ES TODO EL TRUCO: si hay un cliente TCP conectado, la aplicación
    // NO toca el enlace y devuelve null. No se comparte la conversación con el ELM, se
    // ESPERA A QUE ESTÉ LIBRE. Así no hay tramas mezcladas posibles, y el recolector
    // nunca se entera de que estamos aquí.
    //
    // Y no hace falta nada más porque cada cliente que entra re-inicializa el ELM
    // (ATZ, ATE0...): si una pregunta quedara a medias, el cliente siguiente limpia el
    // adaptador al conectarse.
    private static final java.util.concurrent.locks.ReentrantLock CANDADO =
            new java.util.concurrent.locks.ReentrantLock();
    private static volatile boolean HAY_CLIENTE_TCP = false;
    private static volatile BridgeThread BRIDGE_VIVO;

    /** Lo llama el servidor TCP al entrar y al salir un cliente. */
    private static void marcarCliente(boolean hay) {
        CANDADO.lock();
        try {
            HAY_CLIENTE_TCP = hay;
        } finally {
            CANDADO.unlock();
        }
    }

    /** ¿Está el enlace ocupado por un cliente TCP (el recolector)? */
    public static boolean enlaceOcupado() {
        return HAY_CLIENTE_TCP;
    }

    /**
     * Pregunta al ELM327 directamente, sin abrir ninguna conexión.
     *
     * Devuelve la respuesta cruda (la interpreta ObdParse, que ya está probado), o
     * null si el enlace está ocupado o no hay puente. NUNCA espera indefinidamente ni
     * interrumpe a un cliente.
     *
     * @param comando   por ejemplo "010D" (velocidad)
     * @param timeoutMs cuánto esperar como mucho a la respuesta
     */
    public static String preguntar(String comando, long timeoutMs) {
        BridgeThread b = BRIDGE_VIVO;
        if (b == null || b.btIn == null || b.btOut == null) return null;
        if (!CANDADO.tryLock()) return null;          // hay una pregunta en curso
        try {
            if (HAY_CLIENTE_TCP) return null;         // el enlace es del cliente: no se toca
            return b.transaccion(comando, timeoutMs);
        } catch (Throwable t) {
            Diario.error("OBD", "fallo preguntando " + comando + " al ELM", t);
            return null;
        } finally {
            CANDADO.unlock();
        }
    }
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

        // v4.8.1: arrancar el CanSnifferService desde aquí (no solo desde
        // MainActivity.carLaunchMode): si la app ya está abierta, onCreate de
        // MainActivity no se vuelve a llamar y el sniffer CAN nunca arranca.
        // BridgeService se inicia por todos los caminos (BootReceiver, botón,
        // carLaunchMode), así que este es el punto de anclaje fiable.
        try {
            Intent can = new Intent(this, CanSnifferService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(can);
            } else {
                startService(can);
            }
        } catch (Exception e) {
            Log.e("BridgeService", "no se pudo arrancar CanSnifferService", e);
        }

        // v5.0.5: vigilante EXTERNO de Termux. Los vigilantes de dentro de
        // Termux (crond) son ciegos a su propia muerte: si Android mata el
        // proceso, el sistema entero queda caído en silencio (2 días el 15/09).
        // Este servicio sí sobrevive, así que es quien puede notarlo y relanzar.
        try {
            TermuxBridge.startWatch(this);
        } catch (Exception e) {
            Log.e("BridgeService", "no se pudo arrancar el vigilante de Termux", e);
        }
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
            // Versión real del manifest (FIX 2026-09-03: decía v3 hardcodeado)
            String ver = "?";
            try {
                ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (Exception ignored) {}
            log("--- VgateBridge v" + ver + " iniciado ---");
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
        // Los flujos del Bluetooth, como campos: la petición directa desde la app los
        // necesita, y antes eran variables locales de bridgeLoop.
        private volatile java.io.InputStream btIn;
        private volatile java.io.OutputStream btOut;
        private ServerSocket serverSocket;
        private volatile Socket tcpSocket;

        BridgeThread(String mac) {
            // El puente se hace localizable para la pregunta directa de la aplicación.
            BRIDGE_VIVO = this;
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
                // Fase 1: conexión BT PERSISTENTE (se mantiene entre clientes TCP)
                if (!connectBluetooth()) {
                    log("BT no disponible, reintentando en 10s...");
                    broadcastStatus(STATUS_WAITING, "Reintentando BT en 10s...");
                    sleep(10000);
                    continue;
                }

                // Fase 2: servir clientes TCP con el MISMO BT (no se recicla por cliente)
                boolean btSano = serveClients();

                // Limpieza tras caída del BT o del server
                closeSilently(btSocket);
                closeSilently(tcpSocket);
                closeSilently(serverSocket);
                btSocket = null;
                tcpSocket = null;
                serverSocket = null;

                if (btSano) {
                    log("Server TCP reiniciado, reconectando BT en 5s...");
                    broadcastStatus(STATUS_WAITING, "Reiniciando puente en 5s...");
                } else {
                    log("Conexión BT perdida, reconectando en 5s...");
                    broadcastStatus(STATUS_WAITING, "Reconectando BT en 5s...");
                }
                sleep(5000);
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

                // Cancel discovery BEFORE creating socket (critical in some ROMs)
                adapter.cancelDiscovery();

                // Try reflection-based channel 1 first (works better on Chinese ROMs)
                try {
                    java.lang.reflect.Method m = device.getClass().getMethod(
                        "createRfcommSocket", int.class);
                    btSocket = (BluetoothSocket) m.invoke(device, 1);
                    log("Socket creado por reflection (channel 1)");
                } catch (Exception e) {
                    log("Reflection fallo, probando UUID SPP: " + e.getMessage());
                    btSocket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                }

                btSocket.connect();
                log("Bluetooth conectado!");
                return true;

            } catch (Exception e) {
                log("Error BT: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                return false;
            }
        }

        /**
         * Servidor TCP persistente: acepta clientes en bucle usando el MISMO
         * Bluetooth. Solo devuelve false (y cierra) si el BT se cae o el server
         * falla; la desconexión de un cliente NO recicla el BT (v7).
         */
        /**
         * Una pregunta y su respuesta por el enlace Bluetooth.
         *
         * Solo se llama con el candado tomado y sin cliente TCP conectado, así que
         * nadie más está leyendo ni escribiendo en este enlace.
         */
        String transaccion(String comando, long timeoutMs) {
            try {
                btOut.write((comando + "\r").getBytes());
                btOut.flush();

                StringBuilder sb = new StringBuilder(64);
                long limite = System.currentTimeMillis() + timeoutMs;
                byte[] buf = new byte[256];
                while (System.currentTimeMillis() < limite) {
                    if (btIn.available() > 0) {
                        int n = btIn.read(buf);
                        if (n > 0) {
                            sb.append(new String(buf, 0, n));
                            // El ELM termina cada respuesta con el prompt '>'
                            if (sb.indexOf(">") >= 0) break;
                        }
                    } else {
                        sleep(20);
                    }
                }
                if (sb.length() == 0) return null;
                return sb.toString();
            } catch (Throwable t) {
                Diario.error("OBD", "transaccion fallida con " + comando, t);
                return null;
            }
        }

        private boolean serveClients() {
            try {
                // Defensa: cerrar cualquier serverSocket previo que pudiera quedar vivo
                closeSilently(serverSocket);
                serverSocket = null;

                serverSocket = new ServerSocket(TCP_PORT);
                log("Servidor TCP en puerto " + TCP_PORT + " - esperando cliente...");
                broadcastStatus(STATUS_WAITING, "Esperando cliente TCP en :" + TCP_PORT);

                while (running) {
                    // Sin timeout: el BT ya está conectado, esperamos clientes indefinidamente
                    Socket client = serverSocket.accept();
                    client.setSoTimeout(5000);
                    tcpSocket = client; // para stopBridge
                    // A partir de aquí el enlace es de este cliente: la aplicación deja
                    // de preguntar hasta que se suelte. Es lo que evita que el launcher
                    // y el recolector se pisen la conversación con el ELM.
                    marcarCliente(true);
                    log("Cliente TCP conectado: " + client.getInetAddress().getHostAddress());

                    boolean btAlive = bridgeLoop(client);
                    closeSilently(client);
                    tcpSocket = null;
                    marcarCliente(false);

                    if (!btAlive) {
                        log("BT caído durante cliente, reciclando puente...");
                        closeSilently(serverSocket);
                        serverSocket = null;
                        return false;
                    }

                    // BT sigue sano → siguiente cliente SIN reconectar BT
                    log("Cliente desconectado, esperando siguiente...");
                    broadcastStatus(STATUS_WAITING, "Esperando cliente TCP en :" + TCP_PORT);
                }

                closeSilently(serverSocket);
                serverSocket = null;
                return true;
            } catch (Exception e) {
                log("Error TCP server: " + e.getMessage());
                // Liberar el puerto aunque el bind/accept haya fallado a medias
                closeSilently(serverSocket);
                serverSocket = null;
                return false;
            }
        }

        /**
         * Puente BT↔TCP para un cliente. Devuelve true si el BT sigue sano
         * tras atender al cliente; false si el BT se cayó (hay que reconectar).
         */
        private boolean bridgeLoop(final Socket client) {
            final java.util.concurrent.atomic.AtomicBoolean btDead = new java.util.concurrent.atomic.AtomicBoolean(false);
            try {
                // A los CAMPOS, no a variables locales: la pregunta directa desde la
                // app lee de aquí. Y al ser campos, las clases anónimas de abajo los
                // capturan sin necesidad de declararlos final.
                btIn = btSocket.getInputStream();
                btOut = btSocket.getOutputStream();
                final InputStream tcpIn = client.getInputStream();
                final OutputStream tcpOut = client.getOutputStream();

                // Set read timeout on TCP socket so blocking reads detect disconnects
                try { client.setSoTimeout(5000); } catch (Exception e) {}

                // Initialize ELM327
                btOut.write("ATZ\r\n".getBytes()); btOut.flush(); sleep(1500);
                btOut.write("ATE0\r\n".getBytes()); btOut.flush(); sleep(500);
                btOut.write("ATL0\r\n".getBytes()); btOut.flush(); sleep(500);
                btOut.write("ATSP0\r\n".getBytes()); btOut.flush(); sleep(500);

                log("ELM327 inicializado");
                broadcastStatus(STATUS_CONNECTED, "Puente activo en :" + TCP_PORT);

                Thread btToTcp = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        byte[] buf = new byte[4096];
                        try {
                            while (running) {
                                if (btIn.available() > 0) {
                                    int n = btIn.read(buf);
                                    if (n > 0) {
                                        tcpOut.write(buf, 0, n);
                                        tcpOut.flush();
                                    } else if (n == -1) break;
                                } else {
                                    sleep(50);
                                }
                            }
                        } catch (Exception e) {
                            if (running && !Thread.currentThread().isInterrupted()) {
                                btDead.set(true);
                                log("BT->TCP error: " + e.getMessage());
                            }
                        }
                    }
                });

                Thread tcpToBt = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        byte[] buf = new byte[4096];
                        try {
                            while (running) {
                                int n;
                                try {
                                    n = tcpIn.read(buf);
                                } catch (java.net.SocketTimeoutException e) {
                                    continue; // timeout, no data yet
                                }
                                if (n > 0) {
                                    btOut.write(buf, 0, n);
                                    btOut.flush();
                                } else if (n == -1) break;
                            }
                        } catch (Exception e) {
                            if (running) log("TCP->BT error: " + e.getMessage());
                        }
                    }
                });

                btToTcp.start();
                tcpToBt.start();

                // Monitor: wait for either thread to die (client gone or BT down)
                while (running && btToTcp.isAlive() && tcpToBt.isAlive()) {
                    sleep(2000);
                }

                btToTcp.interrupt();
                tcpToBt.interrupt();

                log("Puente desconectado");
                return !btDead.get();

            } catch (Exception e) {
                log("Error en puente: " + e.getMessage());
                return false;
            }
        }

        private void sleep(int ms) {
            try { Thread.sleep(ms); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private boolean isAlive(InputStream in) {
            try {
                // Non-destructive test: if available() throws, socket is dead
                in.available();
                return true;
            } catch (Exception e) {
                return false;
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
