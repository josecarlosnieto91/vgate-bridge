package com.cassiopeia.vgatebridge;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;

/**
 * El diario de la app: lo que hay que saber para diagnosticar una instalación.
 *
 * POR QUÉ NO BASTA CON LOGCAT: la tablet se apaga con el contacto y el logcat se
 * pierde con ella. Además, cuando algo va mal de verdad, el coche está en marcha y
 * yo no estoy delante. Por eso todo lo que se escribe va a DOS sitios:
 *
 *  1. logcat, con la etiqueta "PolarStar" y en nivel INFO y superiores, porque las
 *     ROM de estos cacharros silencian los mensajes de depuración.
 *  2. Un fichero, diagnosticos.txt, dentro de la carpeta de la app: sobrevive al
 *     apagón y se puede traer por SSH sin tener el coche cerca.
 *
 * Las últimas líneas se guardan en memoria para poder volcarlas de golpe cuando
 * interese, sin depender de que el logcat siga ahí.
 *
 * QUÉ SE REGISTRA: eventos (arranque, cambio de tema, apertura de aplicaciones),
 * problemas (permisos, apps que no están, datos que no llegan) y estado periódico.
 * NO se registra cada refresco de pantalla: a 150 ms por vuelta, el fichero sería
 * ilegible justo cuando hace falta leerlo.
 */
final class Diario {

    /** Etiqueta para filtrar: `adb logcat -s PolarStar` o en Termux, lo mismo. */
    static final String ETIQUETA = "PolarStar";

    private static final int MAXIMO = 300;
    private static final Deque<String> BUFFER = new ArrayDeque<String>();
    private static final SimpleDateFormat HORA = new SimpleDateFormat("HH:mm:ss.SSS", new Locale("es", "ES"));

    private Diario() {}

    /** Un evento que interesa: arranque, tema, apertura de una app. */
    static void info(String area, String mensaje) {
        anota("INFO", area, mensaje, null);
    }

    /** Algo no va bien pero la pantalla sigue: falta un permiso, no está una app. */
    static void aviso(String area, String mensaje) {
        anota("AVISO", area, mensaje, null);
    }

    /**
     * Una excepción que se ha decidido no propagar (para no dejar a la tablet sin
     * interfaz). Se cuenta igual: lo que no puede pasar es callarse, porque una
     * excepción silenciada es un fallo que nadie diagnosticará.
     */
    static void error(String area, String mensaje, Throwable t) {
        anota("ERROR", area, mensaje, t);
    }

    private static void anota(String nivel, String area, String mensaje, Throwable t) {
        String linea = HORA.format(new Date()) + " " + nivel + " [" + area + "] " + mensaje
                + (t == null ? "" : " · " + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : ": " + t.getMessage()));
        synchronized (BUFFER) {
            BUFFER.addLast(linea);
            while (BUFFER.size() > MAXIMO) BUFFER.removeFirst();
        }
        // A logcat. INFO y superiores: en estas ROM los DEBUG suelen estar silenciados.
        try {
            if ("ERROR".equals(nivel)) Log.e(ETIQUETA, area + " · " + mensaje, t);
            else if ("AVISO".equals(nivel)) Log.w(ETIQUETA, area + " · " + mensaje);
            else Log.i(ETIQUETA, area + " · " + mensaje);
        } catch (Throwable ignored) {
            // Si el log del sistema falla, el fichero sigue estando.
        }
    }

    /**
     * Cabecera del diario: quién es esta instalación.
     *
     * Es lo primero que quiero saber cuando algo falla en el coche: qué versión, en
     * qué aparato, con qué Android y en qué carpeta. Sin esto, cualquier diagnótico
     * empieza con una ronda de preguntas.
     */
    static void cabecera(Context ctx) {
        try {
            String version = "?";
            try {
                PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
                version = pi.versionName + " (vc " + pi.versionCode + ")";
            } catch (Throwable ignored) {
            }
            info("Arranque", "PolarStar " + version + " · " + Build.MANUFACTURER + " " + Build.MODEL
                    + " · Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"
                    + " · pantalla " + ctx.getResources().getDisplayMetrics().widthPixels + "x"
                    + ctx.getResources().getDisplayMetrics().heightPixels
                    + " @ " + ctx.getResources().getDisplayMetrics().density + "x");
            info("Arranque", "carpeta de trabajo: " + ctx.getExternalFilesDir(null));
        } catch (Throwable t) {
            error("Arranque", "no se pudo escribir la cabecera", t);
        }
    }

    /**
     * Vuelca el diario a un fichero, con lo acumulado y el estado de ahora mismo.
     *
     * Se escribe completo cada vez (no se añade al final): así el fichero nunca
     * crece sin control en la tablet y siempre contiene la ventana más reciente.
     */
    static void volcar(File destino) {
        if (destino == null) return;
        try {
            StringBuilder sb = new StringBuilder(8192);
            sb.append("# Diario de PolarStar — se reescribe entero, maximo ").append(MAXIMO)
              .append(" lineas\n");
            synchronized (BUFFER) {
                for (String linea : BUFFER) sb.append(linea).append('\n');
            }
            sb.append("\n# Lo que la pantalla dice estar dibujando (pizarra)\n");
            sb.append(Pizarra.json()).append('\n');

            FileOutputStream f = new FileOutputStream(destino);
            OutputStreamWriter w = new OutputStreamWriter(f, "UTF-8");
            try {
                w.write(sb.toString());
            } finally {
                w.close();
            }
        } catch (Throwable ignored) {
            // El diario no puede tumbar aquello que está intentando diagnosticar.
        }
    }

    /** Las últimas líneas, para volcarlas o mirarlas desde una prueba. */
    static String texto() {
        StringBuilder sb = new StringBuilder(2048);
        synchronized (BUFFER) {
            for (String linea : BUFFER) sb.append(linea).append('\n');
        }
        return sb.toString();
    }
}
