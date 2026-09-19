import com.cassiopeia.vgatebridge.ObdParse;

/**
 * Tests de ObdParse (JVM, sin Android).
 *
 * El caso que importa de verdad es {@link #tramaCortaNoSeAdivina()}: el PID 010B
 * (MAP) llegó un día con un byte en vez de dos y el parser antiguo devolvió basura
 * en silencio. Aquí, una trama corta devuelve null y queda registrado para siempre.
 */
public class ObdParseTest {

    private static int fallos = 0;

    public static void main(String[] args) {
        velocidadUnByte();
        velocidadCero();
        rpmDosBytes();
        refrigerante();
        nivelCombustible();
        sinDatosEsNull();
        tramaCortaNoSeAdivina();
        basuraNoRevienta();
        respuestaMultilinea();
        pidEquivocadoEsNull();
        cargaYVoltaje();
        pidNoSoportadoEsNull();

        System.out.println();
        if (fallos == 0) {
            System.out.println("ObdParseTest: OK");
        } else {
            System.out.println("ObdParseTest: " + fallos + " FALLOS");
            System.exit(1);
        }
    }

    /** 010D: un solo byte, km/h. */
    private static void velocidadUnByte() {
        igual(ObdParse.parse("41 0D 4B", "0D"), 75.0, "0x4B = 75 km/h");
        igual(ObdParse.parse("41 0D FF", "0D"), 255.0, "0xFF = 255 km/h (no desborda)");
    }

    /** Velocidad cero es 0, no null: parado es un dato, no una falta de dato. */
    private static void velocidadCero() {
        igual(ObdParse.parse("41 0D 00", "0D"), 0.0, "parado = 0 km/h");
    }

    /** 010C: dos bytes, (256A+B)/4. */
    private static void rpmDosBytes() {
        igual(ObdParse.parse("41 0C 1A F8", "0C"), 1726.0, "0x1AF8/4 = 1726 rpm");
        igual(ObdParse.parse("41 0C 00 00", "0C"), 0.0, "motor parado = 0 rpm");
    }

    /** 0105: un byte menos 40. */
    private static void refrigerante() {
        igual(ObdParse.parse("41 05 43", "05"), 27.0, "0x43-40 = 27 °C");
        igual(ObdParse.parse("41 05 00", "05"), -40.0, "-40 °C es el mínimo del PID");
    }

    /** 012F: un byte escalado a porcentaje. */
    private static void nivelCombustible() {
        Double lleno = ObdParse.parse("41 2F FF", "2F");
        Double vacio = ObdParse.parse("41 2F 00", "2F");
        comprobar(lleno != null && Math.abs(lleno - 100.0) < 0.01, "0xFF = 100 %");
        comprobar(vacio != null && Math.abs(vacio) < 0.01, "0x00 = 0 %");
    }

    /** Las respuestas del ELM que no traen dato. */
    private static void sinDatosEsNull() {
        esNull(ObdParse.parse("NO DATA", "0D"), "NO DATA");
        esNull(ObdParse.parse("SEARCHING...", "0D"), "SEARCHING...");
        esNull(ObdParse.parse("", "0D"), "cadena vacía");
        esNull(ObdParse.parse(null, "0D"), "null");
        esNull(ObdParse.parse("41 0D", "0D"), "respuesta al PID sin bytes de dato");
    }

    /**
     * REGRESIÓN 010B/MAP (2026-09-13): un PID de dos bytes que llega con uno solo
     * NO se puede completar inventando. Null, y que se note.
     */
    private static void tramaCortaNoSeAdivina() {
        esNull(ObdParse.parse("41 0C 1A", "0C"), "rpm con un solo byte → null, no media lectura");
        esNull(ObdParse.parse("41 0D 4", "0D"), "byte a medias ('4') → null, no 0x04");
        esNull(ObdParse.parse("41 0D ZZ", "0D"), "byte no hexadecimal → null");
    }

    /** Basura variada: nunca excepción. */
    private static void basuraNoRevienta() {
        esNull(ObdParse.parse("OK", "0D"), "'OK'");
        esNull(ObdParse.parse("ATZ", "0D"), "'ATZ'");
        esNull(ObdParse.parse("41", "0D"), "solo '41'");
        esNull(ObdParse.parse("   ", "0D"), "solo espacios");
        esNull(ObdParse.parse("41 0D 4B 41 0D", "0D"), "trama mezclada y cortada");
    }

    /** El ELM a veces contesta en varias líneas. */
    private static void respuestaMultilinea() {
        igual(ObdParse.parse("SEARCHING...\r41 0D 4B", "0D"), 75.0, "dato en la segunda línea");
        igual(ObdParse.parse("41 0D 4B\r\r>", "0D"), 75.0, "con eco del prompt");
        igual(ObdParse.parse("41 0d 4b", "0D"), 75.0, "minúsculas");
    }

    /** Respuesta de otro PID: no vale. */
    private static void pidEquivocadoEsNull() {
        esNull(ObdParse.parse("41 05 43", "0D"), "llega 05 cuando se pide 0D");
    }

    /** Carga del motor y voltaje: los dos PIDs que faltaban para el diagnóstico. */
    private static void cargaYVoltaje() {
        igual(ObdParse.parse("41 04 80", "04"), 50.196, "carga 41 04 80 → ~50 %");
        igual(ObdParse.parse("41 42 36 4C", "42"), 13.9, "voltaje 41 42 36 4C → 13,9 V");
        esNull(ObdParse.parse("41 42 FF FF", "42"), "voltaje absurdo (200 V) → null, no se inventa");
    }

    /** PID que esta clase no interpreta: null explícito, no basura. */
    private static void pidNoSoportadoEsNull() {
        esNull(ObdParse.parse("41 33 64", "33"), "PID 33 no está en esta clase");
    }

    private static void igual(Double obtenido, double esperado, String mensaje) {
        if (obtenido == null) {
            System.out.println("  ❌ " + mensaje + " → null");
            fallos++;
        } else if (Math.abs(obtenido - esperado) < 0.005) {
            System.out.println("  ✅ " + mensaje + " → " + obtenido);
        } else {
            System.out.println("  ❌ " + mensaje + " → " + obtenido + " (esperado " + esperado + ")");
            fallos++;
        }
    }

    private static void esNull(Double v, String mensaje) {
        if (v == null) {
            System.out.println("  ✅ " + mensaje + " → null");
        } else {
            System.out.println("  ❌ " + mensaje + " → " + v + " (debería ser null)");
            fallos++;
        }
    }

    private static void comprobar(boolean condicion, String mensaje) {
        if (condicion) {
            System.out.println("  ✅ " + mensaje);
        } else {
            System.out.println("  ❌ " + mensaje);
            fallos++;
        }
    }
}
