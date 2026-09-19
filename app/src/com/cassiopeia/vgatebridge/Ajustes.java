package com.cassiopeia.vgatebridge;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Lectura de la configuración de la pantalla y de los ficheros de prueba.
 *
 * Toda la lectura vive DENTRO de la carpeta de la app: la pantalla no lee nada de
 * fuera. Sin bibliotecas JSON: el APK no tiene dependencias y no las va a tener por
 * esto; el formato es plano y el extractor es el mismo que ya usaba el puente.
 *
 * Los ficheros de prueba (test_state.json, test_media.json) son la única forma de
 * ver el launcher funcionando sin coche. En producción no existen, así que la misma
 * ruta de código sirve para las dos cosas y lo que se prueba es lo que se despliega.
 */
final class Ajustes {

    private final File dir;

    Ajustes(File dirArchivos) {
        this.dir = dirArchivos;
    }

    // ── Configuración ────────────────────────────────────────────────────────

    /** Lista de paquetes de launcher.json ("accesos"), o null si no la trae. */
    List<String> accesos() {
        String trozo = leerJson("launcher.json", "accesos");
        return trozo == null ? null : paquetesDe(trozo);
    }

    /** Paquetes de launcher.json ("favoritos"), o null si no la trae. */
    List<String> favoritos() {
        String trozo = leerJson("launcher.json", "favoritos");
        return trozo == null ? null : paquetesDe(trozo);
    }

    /** "auto", "dia" o "noche". Cualquier otra cosa se trata como automático. */
    String tema() {
        String v = leerJson("launcher.json", "tema");
        return v == null ? "auto" : v;
    }

    /** Aplicación de navegación preferida ("navegacion"), o null si no se elige. */
    String navegacion() {
        return leerJson("launcher.json", "navegacion");
    }

    /** Paquetes que se consideran de navegación ("navegacionApps"), o null. */
    List<String> navegacionApps() {
        String trozo = leerJson("launcher.json", "navegacionApps");
        return trozo == null ? null : paquetesDe(trozo);
    }

    /**
     * Qué elementos opcionales se quieren ver ("visibles").
     *
     * Se configura por lista porque es lo que se pide de verdad: quitar de en medio lo
     * que no se usa. Un elemento que no aparece en la lista se considera visible, que
     * es el comportamiento seguro: una configuración a medias no deja la pantalla
     * vacía sin que nadie lo entienda.
     */
    boolean visible(String elemento) {
        String trozo = leerJson("launcher.json", "visibles");
        if (trozo == null) return true;
        List<String> lista = paquetesDe(trozo);
        for (int i = 0; i < lista.size(); i++) {
            if (lista.get(i).equals(elemento)) return true;
        }
        return false;
    }

    /**
     * Con qué arranca la pantalla ("inicio"): "dashboard" (por defecto), "apps" o
     * "navegacion".
     */
    String inicio() {
        String v = leerJson("launcher.json", "inicio");
        if (v == null) return "dashboard";
        if (v.equals("apps") || v.equals("navegacion")) return v;
        return "dashboard";
    }

    /** Extrae los paquetes de un trozo de JSON tipo ["a.b","c.d"]. */
    private static List<String> paquetesDe(String trozo) {
        List<String> salida = new ArrayList<String>();
        int i = 0;
        while (i < trozo.length()) {
            int a = trozo.indexOf('"', i);
            if (a < 0) break;
            int b = trozo.indexOf('"', a + 1);
            if (b < 0) break;
            String v = trozo.substring(a + 1, b).trim();
            if (v.length() > 0) salida.add(v);
            i = b + 1;
        }
        return salida;
    }

    // ── Inyección para pruebas ───────────────────────────────────────────────

    /** ¿Hay estado inyectado? Si lo hay, la pantalla debe pintar eso y no el coche. */
    boolean hayEstadoInyectado() {
        File f = new File(dir, "test_state.json");
        return f.exists() && f.length() > 0;
    }

    /**
     * Vuelca el estado inyectado sobre LiveState.
     *
     * Los que no aparecen se quedan como están (null), que es justo lo que hay que
     * probar: un dato ausente tiene que verse como ausente, no como cero.
     */
    void inyectarEstado(LiveState st) {
        String t = leer("test_state.json");
        if (t == null) return;
        // El fichero inyectado describe el estado COMPLETO: lo que no aparece queda
        // desconocido. Si se conservara el valor anterior, el caso "este dato no
        // llega" no se podria probar, que es justamente el que mas importa.
        st.speedKmh = null; st.rpm = null; st.coolantC = null;
        st.consumptionL100 = null; st.rangeKm = null; st.outsideTempC = null;
        st.fuelLevelPct = null; st.doorOpen = null; st.lightsOn = null;
        Double v;
        v = num(t, "speed");       if (v != null) st.speedKmh = v;
        v = num(t, "rpm");         if (v != null) st.rpm = v;
        v = num(t, "coolant");     if (v != null) st.coolantC = v;
        v = num(t, "consumption"); if (v != null) st.consumptionL100 = v;
        v = num(t, "range");       if (v != null) st.rangeKm = v;
        v = num(t, "fuel");        if (v != null) st.fuelLevelPct = v;
        Double ext = num(t, "outsideTemp");
        if (ext != null) st.outsideTempC = Integer.valueOf((int) Math.round(ext.doubleValue()));
        Boolean bo;
        bo = bool(t, "doorOpen");  if (bo != null) st.doorOpen = bo;
        bo = bool(t, "lightsOn");  if (bo != null) st.lightsOn = bo;
        st.touch();
    }

    /** Contenido de un fichero de prueba ("test_media.json"), o null. */
    String ficheroDePrueba(String nombre) {
        return leer(nombre);
    }

    // ── Mecánica de lectura ──────────────────────────────────────────────────

    /** Lee un fichero de la carpeta de la app, o null si no está o falla. */
    private String leer(String nombre) {
        try {
            File f = new File(dir, nombre);
            if (!f.exists()) return null;
            StringBuilder sb = new StringBuilder((int) Math.max(64, f.length()));
            BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            try {
                char[] buf = new char[1024];
                int n;
                while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
            } finally {
                r.close();
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;   // un fichero ilegible no puede tumbar la pantalla de inicio
        }
    }

    /** Lee un campo de un JSON sin traer una librería: sirve para listas y textos. */
    private String leerJson(String nombre, String campo) {
        String todo = leer(nombre);
        if (todo == null) return null;
        int i = todo.indexOf("\"" + campo + "\"");
        if (i < 0) return null;
        int dosPuntos = todo.indexOf(':', i);
        if (dosPuntos < 0) return null;
        int desde = dosPuntos + 1;
        while (desde < todo.length() && Character.isWhitespace(todo.charAt(desde))) desde++;
        if (desde >= todo.length()) return null;
        char c = todo.charAt(desde);
        if (c == '[') {
            int fin = todo.indexOf(']', desde);
            return fin < 0 ? null : todo.substring(desde, fin + 1);
        }
        if (c == '"') {
            int fin = todo.indexOf('"', desde + 1);
            return fin < 0 ? null : todo.substring(desde + 1, fin);
        }
        int fin = desde;
        while (fin < todo.length() && ",}\n".indexOf(todo.charAt(fin)) < 0) fin++;
        return todo.substring(desde, fin).trim();
    }

    /** Número de un campo del JSON, o null si no está, es null o no es número. */
    private static Double num(String todo, String campo) {
        String v = crudo(todo, campo);
        if (v == null) return null;
        try {
            return Double.valueOf(v);
        } catch (NumberFormatException e) {
            return null;   // "no lo sé" antes que un cero inventado
        }
    }

    /** Booleano de un campo del JSON, o null si no está o es null. */
    private static Boolean bool(String todo, String campo) {
        String v = crudo(todo, campo);
        if (v == null) return null;
        if (v.equals("true")) return Boolean.TRUE;
        if (v.equals("false")) return Boolean.FALSE;
        return null;
    }

    /** Valor crudo: null si el campo falta o vale null. */
    private static String crudo(String todo, String campo) {
        int i = todo.indexOf("\"" + campo + "\"");
        if (i < 0) return null;
        int dosPuntos = todo.indexOf(':', i);
        if (dosPuntos < 0) return null;
        int desde = dosPuntos + 1;
        while (desde < todo.length() && Character.isWhitespace(todo.charAt(desde))) desde++;
        int fin = desde;
        while (fin < todo.length() && ",}\n \t".indexOf(todo.charAt(fin)) < 0) fin++;
        String v = todo.substring(desde, fin).trim();
        return (v.length() == 0 || v.equals("null")) ? null : v;
    }
}
