package com.cassiopeia.vgatebridge;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Lo que la pantalla ha DIBUJADO, no lo que debería dibujar.
 *
 * Cada instrumento apunta aquí el valor con el que acaba de pintarse, al final de su
 * propio onDraw. La Activity vuelca esa pizarra a un fichero cada segundo.
 *
 * ¿Para qué, si ya está el estado del coche? Porque son dos cosas distintas y
 * confundirlas es la forma más fácil de engañarse: el estado dice lo que LLEGÓ y la
 * pizarra dice lo que se PINTÓ. Si un instrumento no dibuja (porque no se refresca,
 * porque se queda sin espacio o porque una excepción lo deja a medias), el estado
 * seguiría diciendo que todo va bien y la pantalla estaría mintiendo.
 *
 * Es el equivalente nativo de leer el DOM: en la versión HTML se comprobaba lo que
 * había en pantalla, no lo que se le había pedido.
 */
final class Pizarra {

    private static final Map<String, String> DIBUJADO = new ConcurrentHashMap<String, String>();

    private Pizarra() {}

    /** Lo llama cada instrumento desde su onDraw, con lo que acaba de pintar. */
    static void anota(String clave, String valor) {
        if (clave == null) return;
        DIBUJADO.put(clave, valor == null ? "" : valor);
    }

    /** Lo dibujado con esa clave, o null si ese instrumento aún no ha pintado. */
    static String lee(String clave) {
        return DIBUJADO.get(clave);
    }

    /** Todo lo dibujado, en JSON plano. null como cadena vacía. */
    static String json() {
        List<String> claves = new ArrayList<String>(DIBUJADO.keySet());
        Collections.sort(claves);
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        for (int i = 0; i < claves.size(); i++) {
            if (i > 0) sb.append(',');
            String k = claves.get(i);
            String v = DIBUJADO.get(k);
            sb.append('"').append(escapa(k)).append("\":\"").append(escapa(v)).append('"');
        }
        sb.append('}');
        return sb.toString();
    }

    /** Vuelca la pizarra a un fichero. Si falla, no pasa nada: es instrumentación. */
    static void volcar(File destino) {
        if (destino == null) return;
        try {
            FileOutputStream f = new FileOutputStream(destino);
            OutputStreamWriter w = new OutputStreamWriter(f, "UTF-8");
            try {
                w.write(json());
            } finally {
                w.close();
            }
        } catch (Throwable ignored) {
            // La comprobación no puede tumbar la pantalla que está comprobando.
        }
    }

    private static String escapa(String v) {
        if (v == null) return "";
        return v.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", " ");
    }
}
