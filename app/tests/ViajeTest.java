import com.cassiopeia.vgatebridge.Viaje;

/**
 * El ordenador de viaje, probado en la JVM.
 *
 * Se importa la clase EXPLÍCITAMENTE, como hacen LiveStateTest y ObdParseTest: este
 * fichero vive en el paquete por defecto y desde ahí no se ve una clase de otro
 * paquete sin el import. Perdí media hora en eso.
 *
 * Java llano con main(), sin JUnit: este proyecto se compila a mano y no trae
 * bibliotecas, igual que LiveStateTest y ObdParseTest. Intentar importar org.junit
 * aquí no compila, y ese fue el fallo de la primera version de este fichero.
 *
 * Estas cuentas son las que nadie revisa a mano: si la integral de la velocidad esta
 * mal, el viaje enseña kilometros que no se han hecho y no hay forma de notarlo.
 */
public class ViajeTest {

    private static final long T0 = 1000000L;
    private static int fallos = 0;
    private static int comprobaciones = 0;

    private static void igual(String que, double esperado, double real, double margen) {
        comprobaciones++;
        boolean bien = Math.abs(esperado - real) <= margen;
        if (!bien) fallos++;
        System.out.println((bien ? "  OK   " : "  FALLA") + "  " + que
                + " → esperado " + esperado + ", real " + real);
    }

    private static void cierto(String que, boolean condicion) {
        comprobaciones++;
        if (!condicion) fallos++;
        System.out.println((condicion ? "  OK   " : "  FALLA") + "  " + que);
    }

    public static void main(String[] args) {
        sesentaSegundosA60();
        laMediaNoEsLaMediaDeLasMuestras();
        paradoNoCuentaKilometrosNiConsumo();
        unHuecoGrandeNoSumaKilometros();
        maximosYMinimos();
        guardarYRecuperar();
        vacioEsVacio();

        System.out.println();
        if (fallos > 0) {
            System.out.println("ViajeTest: " + fallos + " fallo(s) de " + comprobaciones);
            System.exit(1);
        }
        System.out.println("ViajeTest: OK (" + comprobaciones + " comprobaciones)");
    }

    /** 60 muestras a 1 Hz a 60 km/h son exactamente 1 km. */
    private static void sesentaSegundosA60() {
        Viaje v = new Viaje();
        v.reiniciar(T0);
        for (int i = 1; i <= 60; i++) v.muestra(T0 + i * 1000L, 60.0, 2000.0, 6.0, 90.0);
        igual("60 s a 60 km/h son 1 km", 1.0, v.distanciaKm, 0.0001);
    }

    /** La media es distancia entre tiempo, no la media de las muestras. */
    private static void laMediaNoEsLaMediaDeLasMuestras() {
        Viaje v = new Viaje();
        v.reiniciar(T0);
        for (int i = 1; i <= 30; i++) v.muestra(T0 + i * 1000L, 100.0, 2500.0, 8.0, 90.0);
        for (int i = 31; i <= 60; i++) v.muestra(T0 + i * 1000L, 0.0, 800.0, 1.0, 90.0);
        // Promediar muestras daria 50: la media de verdad es 100 km/h en movimiento.
        igual("la media es distancia/tiempo, no media de muestras", 100.0, v.velocidadMedia(), 0.01);
        igual("distancia recorrida", 0.8333, v.distanciaKm, 0.001);
    }

    /** Al ralenti no se avanza: el consumo no se puede ponderar por tiempo. */
    private static void paradoNoCuentaKilometrosNiConsumo() {
        Viaje v = new Viaje();
        v.reiniciar(T0);
        for (int i = 1; i <= 60; i++) v.muestra(T0 + i * 1000L, 0.0, 800.0, 30.0, 90.0);
        igual("parado no suma kilometros", 0.0, v.distanciaKm, 0.0001);
        cierto("sin kilometros no hay consumo medio", Double.isNaN(v.consumoMedio()));
        igual("tiempo con el motor en marcha", 60000.0, v.msMotor, 0.1);
        igual("tiempo moviendose", 0.0, v.msMoviendose, 0.1);
    }

    /** Un hueco grande (la tablet se suspendio) no inventa kilometros. */
    private static void unHuecoGrandeNoSumaKilometros() {
        Viaje v = new Viaje();
        v.reiniciar(T0);
        v.muestra(T0 + 1000L, 120.0, 3000.0, 9.0, 90.0);
        v.muestra(T0 + 301000L, 120.0, 3000.0, 9.0, 90.0);
        // Lo unico real es el primer segundo (33 m a 120 km/h). El hueco de 5 minutos
        // NO aporta nada: si se integrara, serian 10 km que no se han hecho. La
        // primera version de esta comprobacion esperaba 0 y fallaba por eso: el test
        // estaba mal, no el codigo.
        igual("el primer segundo si cuenta (33 m a 120 km/h)", 0.03333, v.distanciaKm, 0.0001);
        cierto("el hueco de 5 minutos no inventa kilometros", v.distanciaKm < 0.05);
    }

    /** Maximos y minimos de lo que se ha visto. */
    private static void maximosYMinimos() {
        Viaje v = new Viaje();
        v.reiniciar(T0);
        v.muestra(T0 + 1000L, 50.0, 1500.0, 5.0, 70.0);
        v.muestra(T0 + 2000L, 130.0, 4200.0, 12.0, 96.0);
        v.muestra(T0 + 3000L, 80.0, 2000.0, 7.0, 88.0);
        igual("velocidad maxima", 130.0, v.velMax, 0.001);
        igual("regimen maximo", 4200.0, v.rpmMax, 0.001);
        igual("temperatura minima", 70.0, v.tempMin, 0.001);
        igual("temperatura maxima", 96.0, v.tempMax, 0.001);
    }

    /** Lo guardado se recupera igual; un texto corrupto no falsea nada. */
    private static void guardarYRecuperar() {
        Viaje v = new Viaje();
        v.reiniciar(T0);
        for (int i = 1; i <= 60; i++) v.muestra(T0 + i * 1000L, 90.0, 2400.0, 7.0, 91.0);
        Viaje otro = new Viaje();
        otro.recupera(v.serializa(), T0 + 100000L);
        igual("lo guardado se recupera", v.distanciaKm, otro.distanciaKm, 0.0001);
        igual("y los maximos tambien", v.velMax, otro.velMax, 0.0001);
        igual("y las muestras", v.muestrasRpm, otro.muestrasRpm, 0.001);

        Viaje roto = new Viaje();
        roto.recupera("basura;;;", T0);
        cierto("un fichero corrupto deja el viaje vacio, no a medias", roto.vacio());
    }

    /** Un viaje sin datos se declara vacio en vez de enseñar ceros. */
    private static void vacioEsVacio() {
        Viaje v = new Viaje();
        v.reiniciar(T0);
        cierto("un viaje sin datos esta vacio", v.vacio());
        cierto("y su media no se puede dar", Double.isNaN(v.velocidadMedia()));
        cierto("y su duracion es una raya, no un cero", "—".equals(Viaje.duracion(0)));
    }
}
