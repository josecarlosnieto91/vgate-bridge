package com.cassiopeia.vgatebridge;

import java.util.Locale;

/**
 * Parser de respuestas OBD crudas del bridge (ELM327). Clase PURA: sin Android,
 * probable en JVM.
 *
 * POR QUÉ EXISTE ESTA CLASE APARTE:
 * el 2026-09-13 el PID 010B (MAP) venía con UN solo byte en vez de dos y el
 * parser antiguo devolvía basura sin avisar. El fallo costó semanas de datos
 * malos. Aquí todo lo que no encaje devuelve {@code null} — "no lo sé" — y
 * NUNCA una excepción: un parser que revienta en mitad de un viaje deja la
 * pantalla muerta.
 *
 * Formato de entrada típico: "41 0D 4B" (respuesta al modo 01, PID 0D, dato 0x4B).
 * También "SEARCHING...", "NO DATA" o cadenas vacías: todas → null.
 */
public final class ObdParse {

    private ObdParse() {}

    /**
     * Interpreta una respuesta OBD cruda.
     *
     * @param respuesta línea cruda del ELM (puede traer prefijos y espacios)
     * @param pid       PID en hexadecimal sin el 01 del modo, p.ej. "0D"
     * @return el valor en unidades físicas, o {@code null} si no se puede saber
     */
    public static Double parse(String respuesta, String pid) {
        int[] datos = datosDe(respuesta, pid);
        if (datos == null || datos.length == 0) return null;

        if ("0D".equals(pid)) {                    // velocidad: 1 byte, km/h
            return datos.length == 1 ? (double) datos[0] : null;
        }
        if ("0C".equals(pid)) {                     // rpm: 2 bytes, (256A+B)/4
            return datos.length == 2 ? ((datos[0] << 8) | datos[1]) / 4.0 : null;
        }
        if ("05".equals(pid)) {                     // refrigerante: 1 byte, A-40 °C
            return datos.length == 1 ? datos[0] - 40.0 : null;
        }
        if ("2F".equals(pid)) {                     // nivel combustible: 1 byte, % = A*100/255
            return datos.length == 1 ? datos[0] * 100.0 / 255.0 : null;
        }
        if ("04".equals(pid)) {                     // carga del motor: 1 byte, % = A*100/255
            return datos.length == 1 ? datos[0] * 100.0 / 255.0 : null;
        }
        if ("42".equals(pid)) {                     // voltaje del módulo: 2 bytes, mV
            // (256A+B)/1000 da voltios. Un valor fuera de rango no se cree: un coche
            // de 12 V no lee 200 V, y devolverlo seria inventar un dato.
            if (datos.length != 2) return null;
            double v = ((datos[0] << 8) | datos[1]) / 1000.0;
            return (v < 5.0 || v > 20.0) ? null : v;
        }
        return null;                                // PID no soportado por esta clase
    }

    /**
     * Devuelve los bytes de datos de una respuesta, o {@code null} si la respuesta
     * no es una respuesta válida a ese PID.
     *
     * Acepta varias líneas (el ELM a veces responde "SEARCHING..." y luego el
     * dato) y busca la línea que empiece por 41 seguido del PID.
     */
    static int[] datosDe(String respuesta, String pid) {
        if (respuesta == null) return null;
        String pidBuscado = pid.toUpperCase(Locale.US);
        for (String linea : respuesta.split("[\r\n]+")) {
            String limpia = linea.replaceAll("[^0-9A-Fa-f ]", " ").trim().replaceAll("\\s+", " ");
            if (limpia.isEmpty()) continue;
            String[] partes = limpia.split(" ");
            if (partes.length < 2) continue;
            if (!"41".equalsIgnoreCase(partes[0])) continue;
            if (!pidBuscado.equalsIgnoreCase(partes[1])) continue;
            if (partes.length < 3) return null;      // respuesta al PID sin dato
            int[] datos = new int[partes.length - 2];
            for (int i = 2; i < partes.length; i++) {
                if (partes[i].length() != 2) return null;   // media trama: no se adivina
                try {
                    datos[i - 2] = Integer.parseInt(partes[i], 16);
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            return datos;
        }
        return null;
    }
}
