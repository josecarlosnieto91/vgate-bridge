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
    /** Alguna puerta abierta (CAN 1281/58). */
    public volatile Boolean doorOpen;
    /** Alumbrado encendido (CAN 1281/56, byte 3). */
    public volatile Boolean lightsOn;
    /** Momento de la última actualización, ms desde epoch. */
    public volatile long updatedAt;

    /** Marca que algo se ha actualizado (lo llama quien escribe los datos). */
    public void touch() {
        updatedAt = System.currentTimeMillis();
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
