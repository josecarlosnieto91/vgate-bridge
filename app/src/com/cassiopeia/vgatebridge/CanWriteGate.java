package com.cassiopeia.vgatebridge;

/**
 * Decide si una lectura del CAN ID 51 merece escribirse en el CSV.
 *
 * Lógica pura (sin Android) para poder probarla fuera del dispositivo con
 * javac/java a secas — el Handler de CanSnifferService solo delega aquí.
 *
 *  - En movimiento: como mucho 1 fila cada 2s con el mismo consumo (el
 *    decodificador repite el valor cada segundo).
 *  - Parado (consumo medio N/D, `FFFF`): el RANGO sigue llegando y es lo que
 *    alimenta el detector de repostajes y la línea "Restante" → 1 fila cada
 *    30s, o al instante si el rango cambia (salto = repostaje).
 */
final class CanWriteGate {

    static final long MIN_INTERVAL_MS = 2000;    // en movimiento
    static final long IDLE_INTERVAL_MS = 30000;  // parado (valor casi constante)

    private long lastWrite = 0;
    private int lastConsRaw = -1;
    private int lastRange = -1;

    /** @return true si la fila debe escribirse (y actualiza el estado). */
    boolean shouldWrite(int consRaw, int range, long now) {
        if (consRaw == 0xFFFF) {
            if (range <= 0) return false;
            if (range == lastRange && now - lastWrite < IDLE_INTERVAL_MS) return false;
        } else if (consRaw == lastConsRaw && now - lastWrite < MIN_INTERVAL_MS) {
            return false;
        }
        lastConsRaw = consRaw;
        lastRange = range;
        lastWrite = now;
        return true;
    }
}
