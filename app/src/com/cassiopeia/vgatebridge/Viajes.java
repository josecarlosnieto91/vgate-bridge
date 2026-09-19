package com.cassiopeia.vgatebridge;

import java.io.File;
import java.util.Calendar;

/**
 * Los tres ámbitos del ordenador de viaje: el viaje actual, el día y el total.
 *
 * Están juntos y viven en el proceso porque los alimenta la MISMA muestra del estado
 * del coche: si cada uno se alimentara por su cuenta, podrían contar cosas distintas
 * del mismo instante. La pantalla los lee de aquí.
 *
 * LO QUE SE GUARDA Y LO QUE NO: el viaje actual vive solo en memoria, porque un viaje
 * es lo que pasa entre que arrancas y paras; si la tablet se apaga, ese viaje se ha
 * terminado. El día y el total SÍ se guardan en disco, porque son acumulados que
 * perderían sentido si desaparecieran al apagar el coche.
 *
 * No se guarda nada que no se pueda recuperar: si el fichero está corrupto, el
 * acumulador se queda vacío y lo dice, en vez de inventar kilómetros.
 */
final class Viajes {

    static final Viajes INSTANCIA = new Viajes();

    /** El viaje desde que se arrancó (o desde el último reinicio a mano). */
    final Viaje actual = new Viaje();
    /** Lo de hoy. Se reinicia al cambiar de día. */
    final Viaje hoy = new Viaje();
    /** Todo lo acumulado desde que se instaló, guardado en disco. */
    final Viaje total = new Viaje();

    private int diaGuardado = -1;
    private boolean cargado;

    private Viajes() {}

    /** El fichero donde vive el acumulado que sobrevive al apagón. */
    private static File fichero(File dir) {
        return new File(dir, "viajes.txt");
    }

    /** Recupera lo guardado y ajusta el día. Se llama una vez al arrancar. */
    void cargar(File dir) {
        if (cargado) return;
        cargado = true;
        long ahora = System.currentTimeMillis();
        actual.reiniciar(ahora);
        hoy.reiniciar(ahora);
        total.reiniciar(ahora);

        String texto = Pizarra.lee("viajes");   // reutiliza el lector de ficheros pequeño
        try {
            if (dir != null && fichero(dir).exists()) {
                texto = leer(fichero(dir));
            }
        } catch (Throwable t) {
            Diario.error("Viajes", "no se pudo leer el acumulado guardado", t);
        }
        if (texto != null) {
            String[] partes = texto.split("\n", -1);
            if (partes.length >= 3) {
                total.recupera(partes[0], ahora);
                hoy.recupera(partes[1], ahora);
                try {
                    diaGuardado = Integer.parseInt(partes[2].trim());
                } catch (Throwable ignored) {
                }
            }
        }
        int hoyDia = Calendar.getInstance().get(Calendar.DAY_OF_YEAR);
        if (diaGuardado != hoyDia) {
            // Día nuevo: lo de hoy empieza de cero. El total sigue.
            hoy.reiniciar(ahora);
            diaGuardado = hoyDia;
            Diario.info("Viajes", "dia nuevo: el acumulado de hoy empieza de cero");
        }
        Diario.info("Viajes", "recuperado: total " + Viaje.num(total.distanciaKm, 1, "km")
                + " en " + total.muestrasVel + " muestras; hoy "
                + Viaje.num(hoy.distanciaKm, 1, "km"));
    }

    /** Guarda el día y el total. Se llama al salir y cada 30 s. */
    void guardar(File dir) {
        if (dir == null) return;
        try {
            java.io.FileOutputStream f = new java.io.FileOutputStream(fichero(dir));
            java.io.OutputStreamWriter w = new java.io.OutputStreamWriter(f, "UTF-8");
            try {
                w.write(total.serializa() + "\n" + hoy.serializa() + "\n" + diaGuardado + "\n");
            } finally {
                w.close();
            }
        } catch (Throwable t) {
            Diario.error("Viajes", "no se pudo guardar el acumulado", t);
        }
    }

    /**
     * Mete una muestra en los tres ámbitos a la vez.
     *
     * Si el día ha cambiado mientras el coche estaba en marcha (un viaje de madrugada),
     * lo de hoy se reinicia aquí: el acumulado diario no puede arrastrar el día
     * anterior solo porque nadie apagó la tablet.
     */
    void muestra(long ahoraMs, Double vel, Double rpm, Double consumo, Double temp) {
        int hoyDia = Calendar.getInstance().get(Calendar.DAY_OF_YEAR);
        if (diaGuardado != hoyDia) {
            diaGuardado = hoyDia;
            hoy.reiniciar(ahoraMs);
            Diario.info("Viajes", "cambio de dia durante la marcha: hoy vuelve a cero");
        }
        actual.muestra(ahoraMs, vel, rpm, consumo, temp);
        hoy.muestra(ahoraMs, vel, rpm, consumo, temp);
        total.muestra(ahoraMs, vel, rpm, consumo, temp);
    }

    /** El botón de reiniciar: solo el viaje actual. Lo de hoy y el total siguen. */
    void reiniciarViaje() {
        long ahora = System.currentTimeMillis();
        actual.reiniciar(ahora);
        Diario.info("Viajes", "viaje actual reiniciado a mano");
    }

    private static String leer(File f) throws Exception {
        StringBuilder sb = new StringBuilder(256);
        java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
        try {
            char[] buf = new char[256];
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
        } finally {
            r.close();
        }
        return sb.toString();
    }
}
