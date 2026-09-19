package com.cassiopeia.vgatebridge;

import java.util.Locale;

/**
 * Un acumulador de viaje.
 *
 * Es una clase PURA (sin Android a proposito), igual que LiveState y ObdParse: asi se
 * prueba en la JVM en Cassiopeia, sin emulador y sin coche. Las cuentas de un ordenador
 * de viaje son exactamente lo que no puede estar mal, porque nadie las revisa a mano.
 *
 * DONDE OTRAS APLICACIONES MIENTEN Y AQUI NO:
 *
 *  - La distancia sale de integrar la VELOCIDAD REAL DEL ECU con el tiempo, no del GPS.
 *    Es la ventaja de tener la fuente propia: el GPS se reserva para lo que de verdad
 *    necesita posicion.
 *  - La velocidad media es distancia entre tiempo, NO la media de las muestras. Si se
 *    promedian muestras, parar veinte minutos en un semaforo cuenta igual que veinte
 *    minutos a 100, y la media sale mal.
 *  - El consumo medio se pondera por distancia: al ralenti se consume sin avanzar, y
 *    promediar por tiempo infla el resultado.
 *  - Si un dato no llega, no se inventa: se acumula lo que hay y los contadores dicen
 *    cuantas muestras validas se usaron. Una media sin muestras se declara no disponible.
 */
public final class Viaje {

    public double distanciaKm;
    public long msMotor;
    public long msMoviendose;
    public double sumaConsumoPorKm;
    public double kmConConsumo;
    public double litros;
    public double sumaRpm;
    public int muestrasRpm;
    public double rpmMax;
    public double velMax;
    public double tempMin = Double.NaN;
    public double tempMax = Double.NaN;
    public long desdeMs;
    public long hastaMs;
    public int muestrasVel;

    private long ultimaMs;

    /** Vacia el acumulador. */
    public void reiniciar(long ahoraMs) {
        distanciaKm = 0; msMotor = 0; msMoviendose = 0;
        sumaConsumoPorKm = 0; kmConConsumo = 0; litros = 0;
        sumaRpm = 0; muestrasRpm = 0; rpmMax = 0; velMax = 0;
        tempMin = Double.NaN; tempMax = Double.NaN;
        desdeMs = ahoraMs; hastaMs = ahoraMs; ultimaMs = ahoraMs;
        muestrasVel = 0;
    }

    /** Mete una muestra del estado del coche. Los nulos son datos que no llegan. */
    public void muestra(long ahoraMs, Double vel, Double rpm, Double consumo, Double temp) {
        if (desdeMs == 0L) desdeMs = ahoraMs;
        long dt = ahoraMs - (ultimaMs == 0L ? ahoraMs : ultimaMs);
        ultimaMs = ahoraMs;
        // Un salto enorme (la tablet se suspendio) NO se integra: contaria kilometros
        // que no se han hecho. Se corta el tramo y se sigue contando desde aqui.
        if (dt < 0 || dt > 10000) dt = 0;
        hastaMs = ahoraMs;
        msMotor += dt;

        if (vel != null && !vel.isNaN() && vel.doubleValue() >= 0) {
            double km = vel.doubleValue() * dt / 3600000.0;
            distanciaKm += km;
            muestrasVel++;
            if (dt > 0 && vel.doubleValue() > 1.0) msMoviendose += dt;
            if (vel.doubleValue() > velMax) velMax = vel.doubleValue();
            if (consumo != null && !consumo.isNaN() && consumo.doubleValue() >= 0 && km > 0) {
                sumaConsumoPorKm += consumo.doubleValue() * km;
                kmConConsumo += km;
                litros += consumo.doubleValue() * km / 100.0;
            }
        }
        if (rpm != null && !rpm.isNaN()) {
            sumaRpm += rpm.doubleValue();
            muestrasRpm++;
            if (rpm.doubleValue() > rpmMax) rpmMax = rpm.doubleValue();
        }
        if (temp != null && !temp.isNaN()) {
            double t = temp.doubleValue();
            if (Double.isNaN(tempMin) || t < tempMin) tempMin = t;
            if (Double.isNaN(tempMax) || t > tempMax) tempMax = t;
        }
    }

    /** Velocidad media = distancia entre tiempo en movimiento. NaN si no se puede. */
    public double velocidadMedia() {
        if (msMoviendose <= 0) return Double.NaN;
        return distanciaKm / (msMoviendose / 3600000.0);
    }

    /** Consumo medio ponderado por distancia. NaN si no hay kilometros con consumo. */
    public double consumoMedio() {
        if (kmConConsumo <= 0) return Double.NaN;
        return sumaConsumoPorKm / kmConConsumo;
    }

    /** Regimen medio de las muestras. NaN si no hay ninguna. */
    public double regimenMedio() {
        if (muestrasRpm == 0) return Double.NaN;
        return sumaRpm / muestrasRpm;
    }

    /** Un acumulador vacio no se enseña como si tuviera datos. */
    public boolean vacio() {
        return distanciaKm <= 0 && muestrasVel == 0 && muestrasRpm == 0;
    }

    public String tiempoMotor() {
        return duracion(msMotor);
    }

    public static String duracion(long ms) {
        if (ms <= 0) return "—";
        long s = ms / 1000;
        long h = s / 3600, m = (s % 3600) / 60;
        if (h > 0) return h + " h " + m + " min";
        if (m > 0) return m + " min " + (s % 60) + " s";
        return s + " s";
    }

    public static String num(double v, int decimales, String unidad) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "—";
        return String.format(Locale.US, "%." + decimales + "f", v) + (unidad == null ? "" : " " + unidad);
    }

    public String serializa() {
        StringBuilder sb = new StringBuilder(128);
        sb.append(distanciaKm).append(';').append(msMotor).append(';').append(msMoviendose);
        sb.append(';').append(sumaConsumoPorKm).append(';').append(kmConConsumo);
        sb.append(';').append(litros).append(';').append(sumaRpm).append(';').append(muestrasRpm);
        sb.append(';').append(rpmMax).append(';').append(velMax);
        sb.append(';').append(tempMin).append(';').append(tempMax);
        sb.append(';').append(desdeMs).append(';').append(hastaMs).append(';').append(muestrasVel);
        return sb.toString();
    }

    /** Recupera lo guardado. Si el texto esta mal, se queda vacio: no se inventa nada. */
    public void recupera(String texto, long ahoraMs) {
        reiniciar(ahoraMs);
        if (texto == null) return;
        try {
            String[] p = texto.trim().split(";");
            if (p.length < 15) return;
            distanciaKm = Double.parseDouble(p[0]);
            msMotor = (long) Double.parseDouble(p[1]);
            msMoviendose = (long) Double.parseDouble(p[2]);
            sumaConsumoPorKm = Double.parseDouble(p[3]);
            kmConConsumo = Double.parseDouble(p[4]);
            litros = Double.parseDouble(p[5]);
            sumaRpm = Double.parseDouble(p[6]);
            muestrasRpm = (int) Double.parseDouble(p[7]);
            rpmMax = Double.parseDouble(p[8]);
            velMax = Double.parseDouble(p[9]);
            tempMin = Double.parseDouble(p[10]);
            tempMax = Double.parseDouble(p[11]);
            desdeMs = (long) Double.parseDouble(p[12]);
            hastaMs = (long) Double.parseDouble(p[13]);
            muestrasVel = (int) Double.parseDouble(p[14]);
        } catch (Throwable t) {
            reiniciar(ahoraMs);
        }
    }
}
