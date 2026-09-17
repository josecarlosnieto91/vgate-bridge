package com.cassiopeia.vgatebridge;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Puente hacia Termux: lanzar su script de arranque y vigilar que siga vivo.
 *
 * ⚠️ Contexto (2026-09-17). El 15/09 Termux estuvo MUERTO dos días sin que
 * nadie lo notara. La causa de fondo: todos los vigilantes viven DENTRO de
 * Termux (crond + polar_boot_extra.sh), así que son ciegos a su propia muerte
 * — si Android mata Termux, mueren con él y nadie lo resucita hasta el
 * siguiente arranque completo de la tablet. La app es el único testigo EXTERNO
 * que sobrevive: su puente siguió vivo (puerto 22000 abierto) con Termux muerto.
 *
 * ⚠️ El RUN_COMMAND a Termux falla EN SILENCIO si falta cualquiera de estas dos:
 *   1. `com.termux.permission.RUN_COMMAND` declarado aquí y CONCEDIDO por el
 *      usuario (Ajustes → Apps → VgateBridge → Permisos → permisos adicionales).
 *   2. `allow-external-apps=true` en `~/.termux/termux.properties` (verificado
 *      en la tablet el 2026-09-17).
 *
 * Por eso el fallo está registrado con Log.e y no se traga sin más: el 15/09
 * costó dos días de datos precisamente por un fallo silencioso.
 */
final class TermuxBridge {

    private static final String TAG = "TermuxBridge";
    private static final String TERMUX_PKG = "com.termux";
    private static final String TERMUX_RUN_SERVICE = "com.termux.app.RunCommandService";
    private static final String TERMUX_ACTION_RUN = "com.termux.RUN_COMMAND";
    private static final String EXTRA_PATH = "com.termux.RUN_COMMAND_PATH";
    private static final String EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND";
    private static final String BOOT_SCRIPT =
            "/data/data/com.termux/files/home/polar_boot_extra.sh";

    /** sshd de Termux (lo levanta el script de arranque): si escucha, Termux vive. */
    private static final int SSH_PORT = 8022;
    private static final int SSH_TIMEOUT_MS = 2000;
    /** Cada cuánto se comprueba: convierte una caída de días en una de 5 min. */
    private static final long WATCH_MS = 5 * 60 * 1000L;

    private static Thread watch;

    private TermuxBridge() {
    }

    /**
     * Lanza `polar_boot_extra.sh` en Termux (levanta sshd, crond, GPS y recolector).
     * Idempotente en destino: el script no duplica nada.
     */
    static void runBootScript(Context ctx) {
        try {
            Intent i = new Intent(TERMUX_ACTION_RUN);
            i.setClassName(TERMUX_PKG, TERMUX_RUN_SERVICE);
            i.putExtra(EXTRA_PATH, BOOT_SCRIPT);
            i.putExtra(EXTRA_BACKGROUND, true);
            ctx.startService(i);
            Log.i(TAG, "RUN_COMMAND enviado a Termux");
        } catch (Exception e) {
            // No debe tumbar el puente. Si esto falla, el crond de Termux sigue
            // siendo la capa fiable mientras Termux siga vivo.
            Log.e(TAG, "RUN_COMMAND rechazado (¿permiso "
                    + "com.termux.permission.RUN_COMMAND sin conceder?)", e);
        }
    }

    /** ¿Termux escucha en su sshd? Basta el connect: solo interesa vivo/muerto. */
    static boolean sshAlive() {
        Socket s = null;
        try {
            s = new Socket();
            s.connect(new InetSocketAddress("127.0.0.1", SSH_PORT), SSH_TIMEOUT_MS);
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            if (s != null) {
                try {
                    s.close();
                } catch (IOException ignored) {
                    // irrelevante
                }
            }
        }
    }

    /**
     * Arranca el vigilante (idempotente). Se ancla al servicio del puente, que
     * es el único que arranca por todos los caminos (boot, botón, modo coche).
     */
    static synchronized void startWatch(final Context ctx) {
        if (watch != null && watch.isAlive()) {
            return;
        }
        watch = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        if (!sshAlive()) {
                            Log.w(TAG, "vigilante: Termux no escucha en " + SSH_PORT
                                    + " → relanzando arranque");
                            runBootScript(ctx);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "vigilante falló (se reintenta en el próximo ciclo)", e);
                    }
                    try {
                        Thread.sleep(WATCH_MS);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, "TermuxWatch");
        watch.setDaemon(true);
        watch.start();
        Log.i(TAG, "vigilante de Termux arrancado (cada " + (WATCH_MS / 1000) + " s)");
    }
}
