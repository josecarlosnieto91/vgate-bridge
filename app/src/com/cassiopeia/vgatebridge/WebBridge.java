package com.cassiopeia.vgatebridge;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.List;

/**
 * Puente entre la pantalla (JavaScript) y la app (Java).
 *
 * Se expone al WebView con el nombre "Android", así que desde la pantalla se llama
 * `window.Android.estado()`. Es la única puerta que tiene el HTML hacia el sistema:
 * por eso es pequeña y todo lo que entra por ella se valida.
 *
 * DE DÓNDE SALE EL ESTADO (por orden):
 *  1. Si existe `test_state.json` en la carpeta de la app, se devuelve TAL CUAL.
 *     Es la puerta de pruebas: permite inyectar datos conocidos y comprobar que la
 *     pantalla los enseña exactamente igual, sin coche y sin emulador de CAN. El
 *     fichero se pone con un `adb push` y se quita borrándolo: en producción no
 *     existe y no afecta.
 *  2. Si no, el estado real del coche (LiveState), que alimentan el sniffer CAN y
 *     el sondeo OBD.
 *
 * NADA DE RED NI DE EJECUCIÓN DE COMANDOS: los únicos métodos que tocan el sistema
 * son abrir una app (validada contra las instaladas) y abrir los ajustes de inicio.
 */
public class WebBridge {

    private final Activity actividad;
    private final LiveState estado;
    private final File dirArchivos;

    public WebBridge(Activity actividad, LiveState estado) {
        this.actividad = actividad;
        this.estado = estado;
        this.dirArchivos = actividad.getExternalFilesDir(null);
    }

    /** Estado del coche en JSON. La pantalla lo pinta tal cual. */
    @android.webkit.JavascriptInterface
    public String estado() {
        String inyectado = leerInyectado();
        if (inyectado != null) return inyectado;
        return estado.toJson();
    }

    /**
     * Lista de accesos (apps) en JSON, o null si no hay fichero de configuración.
     * Devolver null es a propósito: la pantalla usa entonces sus valores por
     * defecto, así que un fichero ausente o roto no deja la barra vacía.
     */
    @android.webkit.JavascriptInterface
    public String accesos() {
        String crudo = leerJson("launcher.json", "apps");
        return crudo;
    }

    /** Tema: "auto" (por el alumbrado), "dia" o "noche". */
    @android.webkit.JavascriptInterface
    public String tema() {
        String crudo = leerJson("launcher.json", "tema");
        if (crudo == null) return "auto";
        return crudo.replace("\"", "").trim();
    }

    /**
     * Abre una app instalada. Se comprueba que el paquete TENGA una pantalla de
     * inicio antes de lanzarlo: si no, se ignora. El HTML no puede pedir que se
     * abra cualquier cosa.
     */
    @android.webkit.JavascriptInterface
    public void abrirApp(String paquete) {
        if (paquete == null || paquete.isEmpty()) return;
        try {
            PackageManager pm = actividad.getPackageManager();
            Intent intencion = pm.getLaunchIntentForPackage(paquete);
            if (intencion == null) return;          // no instalada o sin pantalla
            intencion.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            actividad.startActivity(intencion);
        } catch (Throwable ignored) {
            // Que una app no abra no puede tumbar la pantalla de inicio.
        }
    }

    /**
     * Abre los ajustes de pantalla de inicio del sistema: la salida de emergencia
     * para volver al launcher de la ROM.
     */
    @android.webkit.JavascriptInterface
    public void ajustesInicio() {
        try {
            Intent i = new Intent(Settings.ACTION_HOME_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            actividad.startActivity(i);
        } catch (Throwable t) {
            // Algunas ROM no exponen esa pantalla: se abre la de ajustes a secas.
            try {
                Intent i = new Intent(Settings.ACTION_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                actividad.startActivity(i);
            } catch (Throwable ignored) {}
        }
    }

    /** Apps instaladas con pantalla de inicio (para diagnósticos desde la pantalla). */
    @android.webkit.JavascriptInterface
    public int appsInstaladas() {
        try {
            PackageManager pm = actividad.getPackageManager();
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> r = pm.queryIntentActivities(i, 0);
            return r == null ? 0 : r.size();
        } catch (Throwable t) {
            return -1;
        }
    }

    // ── Lectura de ficheros locales (nunca de fuera de la app) ────────────────

    /** Lee un campo de launcher.json sin traer una librería JSON: extrae la lista. */
    private String leerJson(String nombre, String campo) {
        try {
            File f = new File(dirArchivos, nombre);
            if (!f.exists()) return null;
            String todo = leerFichero(f);
            if (todo == null) return null;
            int i = todo.indexOf("\"" + campo + "\"");
            if (i < 0) return null;
            int dosPuntos = todo.indexOf(':', i);
            if (dosPuntos < 0) return null;
            int desde = dosPuntos + 1;
            while (desde < todo.length() && Character.isWhitespace(todo.charAt(desde))) desde++;
            if (desde >= todo.length()) return null;
            char c = todo.charAt(desde);
            if (c == '[') {                       // lista: hasta el corchete que cierra
                int fin = todo.indexOf(']', desde);
                if (fin < 0) return null;
                return todo.substring(desde, fin + 1);
            }
            if (c == '"') {                       // texto: hasta la comilla que cierra
                int fin = todo.indexOf('"', desde + 1);
                if (fin < 0) return null;
                return todo.substring(desde + 1, fin);
            }
            int fin = desde;
            while (fin < todo.length() && ",}\n".indexOf(todo.charAt(fin)) < 0) fin++;
            return todo.substring(desde, fin).trim();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Estado inyectado para pruebas, o null si no hay fichero. */
    private String leerInyectado() {
        try {
            File f = new File(dirArchivos, "test_state.json");
            if (!f.exists()) return null;
            return leerFichero(f);
        } catch (Throwable t) {
            return null;
        }
    }

    private String leerFichero(File f) {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(f));
            StringBuilder sb = new StringBuilder();
            String linea;
            while ((linea = r.readLine()) != null) sb.append(linea);
            return sb.toString().trim();
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (r != null) r.close(); } catch (Throwable ignored) {}
        }
    }
}
