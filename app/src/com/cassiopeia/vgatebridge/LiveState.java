package com.cassiopeia.vgatebridge;

import java.util.Locale;

/**
 * Estado vivo del coche para la pantalla del launcher.
 *
 * Es una clase PURA (sin Android a propósito): así se puede compilar y probar en
 * la JVM en Cassiopeia, sin emulador ni dispositivo. El launcher la lee y el
 * puente JS la serializa.
 *
 * Reglas de diseño:
 *  - Los campos son volátiles: los escribe el hilo del sniffer/sondeo y los lee
 *    el hilo de la interfaz. Sin ellos habría valores a medias.
 *  - Un dato que no se conoce es {@code null}, NUNCA 0 ni NaN. En la pantalla,
 *    "no lo sé" y "cero" son cosas distintas: un velocímetro marcando 0 cuando el
 *    dato no llega sería una mentira peligrosa.
 *  - {@link #toJson()} no usa bibliotecas: el APK no tiene dependencias y no las
 *    va a tener por esto.
 */
public final class LiveState {

    /**
     * Estado compartido del proceso.
     *
     * El sniffer CAN, el sondeo OBD y la pantalla viven en la MISMA app (y por
     * tanto en el mismo proceso), así que este es el punto donde se encuentran:
     * los servicios escriben aquí y el launcher lee al pintar. Es un singleton
     * porque duplicarlo significaría que la pantalla mira un sitio y el coche
     * escribe en otro — el fallo más tonto posible y el más difícil de ver.
     */
    public static final LiveState INSTANCIA = new LiveState();

    /** Velocidad del ECU (OBD 010D), km/h. */
    public volatile Double speedKmh;
    /** Régimen del motor (OBD 010C), rpm. */
    public volatile Double rpm;
    /** Temperatura del refrigerante (OBD 0105), °C. */
    public volatile Double coolantC;
    /** Consumo instantáneo (CAN 1281/51, bytes 0-1 ×0.1), L/100km. */
    public volatile Double consumptionL100;
    /** Autonomía (CAN 1281/51, bytes 2-3), km. */
    public volatile Double rangeKm;
    /** Temperatura exterior (CAN 1281/54), °C. */
    public volatile Integer outsideTempC;
    /** Nivel de combustible (OBD 012F), %. */
    public volatile Double fuelLevelPct;
    /** Carga del motor (OBD 0104), %. */
    public volatile Double loadPct;
    /** Voltaje de batería (OBD 0142), V. */
    public volatile Double batteryV;
    /** Alguna puerta abierta (CAN 1281/58). */
    public volatile Boolean doorOpen;
    /** Alumbrado encendido (CAN 1281/56, byte 3). */
    public volatile Boolean lightsOn;
    /** Momento de la última actualización, ms desde epoch. */
    public volatile long updatedAt;

    // ── El sello de cada dato, por separado ──────────────────────────────────
    //
    // Un solo "updatedAt" no basta: si el CAN sigue dando puertas mientras el OBD se
    // ha caído, con un sello común parecería que todo está al día. Cada magnitud lleva
    // el suyo, y así la pantalla puede decir "esto es de ahora" y "esto es de hace
    // tres minutos" sin mentir en ninguna de las dos.
    public volatile long speedKmhAt, rpmAt, coolantCAt, loadPctAt, batteryVAt;
    public volatile long consumptionL100At, rangeKmAt, outsideTempCAt, fuelLevelPctAt;
    public volatile long doorOpenAt, lightsOnAt;

    /** Origen de cada dato, para poder decirlo en la pantalla de diagnóstico. */
    public static final String FUENTE_OBD = "OBD";
    public static final String FUENTE_CAN = "CAN";

    /** Marca que algo se ha actualizado (lo llama quien escribe los datos). */
    public void touch() {
        updatedAt = System.currentTimeMillis();
    }

    // ── Escritura: valor y sello juntos ──────────────────────────────────────

    public void ponVelocidad(Double v)        { speedKmh = v;        speedKmhAt = ahora(v); }
    public void ponRegimen(Double v)          { rpm = v;             rpmAt = ahora(v); }
    public void ponRefrigerante(Double v)     { coolantC = v;        coolantCAt = ahora(v); }
    public void ponCarga(Double v)            { loadPct = v;         loadPctAt = ahora(v); }
    public void ponVoltaje(Double v)          { batteryV = v;        batteryVAt = ahora(v); }
    public void ponConsumo(Double v)          { consumptionL100 = v; consumptionL100At = ahora(v); }
    public void ponAutonomia(Double v)        { rangeKm = v;         rangeKmAt = ahora(v); }
    public void ponExterior(Integer v)        { outsideTempC = v;    outsideTempCAt = ahora(v); }
    public void ponCombustible(Double v)      { fuelLevelPct = v;    fuelLevelPctAt = ahora(v); }
    public void ponPuerta(Boolean v)          { doorOpen = v;        doorOpenAt = ahora(v); }
    public void ponLuces(Boolean v)           { lightsOn = v;        lightsOnAt = ahora(v); }

    private static long ahora(Double v) {
        return v == null ? 0L : System.currentTimeMillis();
    }

    private static long ahora(Integer v) {
        return v == null ? 0L : System.currentTimeMillis();
    }

    private static long ahora(Boolean v) {
        return v == null ? 0L : System.currentTimeMillis();
    }

    // ── Consulta: ¿esto es un dato de ahora, o ya no vale? ───────────────────

    /** Sin dato: nunca ha llegado. */
    public static final int SIN_DATO = 0;
    /** Caducado: llegó, pero hace demasiado. */
    public static final int CADUCADO = 1;
    /** Actual: es de ahora mismo. */
    public static final int ACTUAL = 2;

    /**
     * Estado de una lectura a partir de su sello.
     *
     * Es la regla que sostiene todo lo demás: un valor viejo NO es un valor. Enseñarlo
     * como si fuera actual es la forma más fácil de mentir en un cuadro de mando.
     */
    public int estado(long sello, long caducaMs) {
        if (sello == 0L) return SIN_DATO;
        return (System.currentTimeMillis() - sello) <= caducaMs ? ACTUAL : CADUCADO;
    }

    /** Edad en milisegundos, o -1 si nunca ha llegado. */
    public long edad(long sello) {
        if (sello == 0L) return -1L;
        return System.currentTimeMillis() - sello;
    }

    /** Descripción legible de una edad, para la pantalla de diagnóstico. */
    public static String comoEdad(long ms) {
        if (ms < 0) return "nunca";
        if (ms < 1000) return "ahora";
        long s = ms / 1000;
        if (s < 60) return "hace " + s + " s";
        long m = s / 60;
        if (m < 60) return "hace " + m + " min";
        return "hace " + (m / 60) + " h";
    }

    /**
     * Serializa el estado a JSON plano.
     *
     * Los desconocidos salen como {@code null} (no como 0), y los booleanos como
     * {@code true}/{@code false}. Los números se formatean con {@link Locale#US}
     * para que el separador decimal sea el punto: con locale español saldría
     * "4,4" y el JSON no lo leería JavaScript.
     */
    public String toJson() {
        StringBuilder sb = new StringBuilder(192);
        sb.append('{');
        // Los campos se separan con coma, pero NUNCA antes del primero: un JSON
        // que empieza por "{," no lo lee nadie. Lo cazó el test (y el parser de
        // Python) el 2026-09-19.
        Sep sep = new Sep();
        campo(sb, sep, "speed", speedKmh);
        campo(sb, sep, "rpm", rpm);
        campo(sb, sep, "coolant", coolantC);
        campo(sb, sep, "consumption", consumptionL100);
        campo(sb, sep, "range", rangeKm);
        campo(sb, sep, "outsideTemp", outsideTempC);
        campo(sb, sep, "fuel", fuelLevelPct);
        campo(sb, sep, "load", loadPct);
        campo(sb, sep, "battery", batteryV);
        bool(sb, sep, "doorOpen", doorOpen);
        bool(sb, sep, "lightsOn", lightsOn);
        sep.pon(sb);
        sb.append("\"updatedAt\":").append(updatedAt);
        sb.append('}');
        return sb.toString();
    }

    /** Pone la coma solo a partir del segundo campo. */
    private static final class Sep {
        private boolean primero = true;

        void pon(StringBuilder sb) {
            if (primero) {
                primero = false;
            } else {
                sb.append(',');
            }
        }
    }

    private static void campo(StringBuilder sb, Sep sep, String nombre, Double v) {
        sep.pon(sb);
        sb.append('"').append(nombre).append("\":");
        // isNaN/isInfinite: un Double roto no puede colarse en el JSON, porque
        // "NaN" no es JSON válido y dejaría la pantalla en blanco sin decir por qué.
        if (v == null || v.isNaN() || v.isInfinite()) {
            sb.append("null");
        } else {
            sb.append(String.format(Locale.US, "%.2f", v));
        }
    }

    private static void campo(StringBuilder sb, Sep sep, String nombre, Integer v) {
        sep.pon(sb);
        sb.append('"').append(nombre).append("\":");
        sb.append(v == null ? "null" : String.valueOf(v));
    }

    private static void bool(StringBuilder sb, Sep sep, String nombre, Boolean v) {
        sep.pon(sb);
        sb.append('"').append(nombre).append("\":");
        sb.append(v == null ? "null" : v.toString());
    }
}
