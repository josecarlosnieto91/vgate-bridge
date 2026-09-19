import com.cassiopeia.vgatebridge.LiveState;

/**
 * Tests de LiveState (JVM, sin Android).
 *
 * Lo que se comprueba no es "que serialice", sino las dos decisiones que evitan
 * mentir en el salpicadero: un dato desconocido es null (no 0) y un Double roto
 * no se cuela como NaN (que rompería el JSON y dejaría la pantalla en blanco).
 */
public class LiveStateTest {

    private static int fallos = 0;

    public static void main(String[] args) {
        estadoVacioDaNulls();
        valoresSeSerializanConPuntoDecimal();
        nanYInfinitoNoSeCuelan();
        booleanosYNull();
        touchActualizaMarca();

        System.out.println();
        if (fallos == 0) {
            System.out.println("LiveStateTest: OK");
        } else {
            System.out.println("LiveStateTest: " + fallos + " FALLOS");
            System.exit(1);
        }
    }

    /** Un estado recién creado: todo null, nada de ceros. */
    private static void estadoVacioDaNulls() {
        LiveState s = new LiveState();
        String json = s.toJson();
        comprobar(json.contains("\"speed\":null"), "velocidad desconocida debe ser null: " + json);
        comprobar(json.contains("\"doorOpen\":null"), "puerta desconocida debe ser null: " + json);
        comprobar(!json.contains("0.00"), "no debe inventar ceros: " + json);
        comprobar(!json.toLowerCase().contains("nan"), "no debe aparecer NaN: " + json);
        comprobar(json.startsWith("{") && json.endsWith("}"), "empieza y acaba en llaves: " + json);
        // Comas: la comprobación de llaves no bastaba. El 2026-09-19 el JSON salía
        // como "{," y este test daba verde igualmente. Estas tres reglas lo cazan.
        comprobar(!json.contains("{,"), "sin coma tras la llave de apertura: " + json);
        comprobar(!json.contains(",,"), "sin comas dobles: " + json);
        comprobar(!json.contains(",}"), "sin coma antes de cerrar: " + json);
        comprobar(json.split("\"").length % 2 == 1, "comillas equilibradas: " + json);
    }

    /** Los valores salen con punto decimal, no con coma (rompería el JSON en JS). */
    private static void valoresSeSerializanConPuntoDecimal() {
        LiveState s = new LiveState();
        s.speedKmh = 75.0;
        s.consumptionL100 = 4.4;
        s.outsideTempC = 17;
        s.lightsOn = true;
        String json = s.toJson();
        comprobar(json.contains("\"speed\":75.00"), "velocidad: " + json);
        comprobar(json.contains("\"consumption\":4.40"), "consumo con punto: " + json);
        comprobar(!json.contains("4,40"), "no debe haber coma decimal: " + json);
        comprobar(json.contains("\"outsideTemp\":17"), "temperatura entera: " + json);
        comprobar(json.contains("\"lightsOn\":true"), "luces: " + json);
    }

    /** NaN e infinito se convierten en null: no son JSON válido. */
    private static void nanYInfinitoNoSeCuelan() {
        LiveState s = new LiveState();
        s.rpm = Double.NaN;
        s.coolantC = Double.POSITIVE_INFINITY;
        s.rangeKm = Double.NEGATIVE_INFINITY;
        String json = s.toJson();
        comprobar(json.contains("\"rpm\":null"), "NaN debe ser null: " + json);
        comprobar(json.contains("\"coolant\":null"), "infinito debe ser null: " + json);
        comprobar(json.contains("\"range\":null"), "infinito negativo debe ser null: " + json);
    }

    /** Los booleanos false se distinguen de null. */
    private static void booleanosYNull() {
        LiveState s = new LiveState();
        s.doorOpen = Boolean.FALSE;
        String json = s.toJson();
        comprobar(json.contains("\"doorOpen\":false"), "false explícito: " + json);
        comprobar(!json.contains("\"doorOpen\":null"), "false no es null: " + json);
    }

    /** touch() marca el momento de actualización. */
    private static void touchActualizaMarca() {
        LiveState s = new LiveState();
        long antes = s.updatedAt;
        s.touch();
        comprobar(s.updatedAt >= antes, "updatedAt debe avanzar");
        comprobar(s.toJson().contains("\"updatedAt\":"), "updatedAt debe ir en el JSON");
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
