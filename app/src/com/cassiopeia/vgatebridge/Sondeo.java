package com.cassiopeia.vgatebridge;

/**
 * El sondeo de la pantalla: pregunta al ELM a través del bridge.
 *
 * NO ABRE NINGUNA CONEXIÓN. Llama a BridgeService.preguntar(), que vive en este mismo
 * proceso y es quien decide cuándo se puede hablar con el adaptador. Si el recolector
 * de Termux está usando el puerto, la pregunta devuelve null y aquí no se hace nada:
 * ESPERAR, no competir. Así la telemetría y las alertas de Telegram siguen funcionando
 * exactamente igual.
 *
 * DE DÓNDE SALE CADA DATO, Y POR QUÉ:
 *  - OBD (aquí): velocidad, régimen, refrigerante, carga, combustible y voltaje. Son
 *    los que el coche da por el puerto de diagnóstico.
 *  - CAN (lo escribe el sniffer, no esto): consumo, autonomía, temperatura exterior,
 *    puertas y alumbrado. Vienen del decodificador de la unidad.
 *  No se pide por OBD lo que ya llega bien por CAN: sería duplicar la pregunta y gastar
 *  turno del adaptador para nada.
 *
 * Ritmo: despacio a propósito. Cada PID es una pregunta al ELM, y el adaptador es lento.
 * Cuatro PIDs por vuelta cada pocos segundos bastan para un cuadro, y dejan el enlace
 * libre la mayor parte del tiempo para el recolector.
 */
final class Sondeo {

    /** Los PIDs que se preguntan, en orden. Solo los que ObdParse sabe interpretar. */
    private static final String[] PIDS = {"0D", "0C", "05", "04", "2F", "42"};

    private static final long ESPERA_VUELTA = 2000;      // entre vueltas completas
    private static final long ESPERA_OCUPADO = 5000;     // cuando el recolector manda
    private static final long TIMEOUT_PID = 400;         // por pregunta

    private Thread hilo;
    private volatile boolean activo;
    private boolean avisadoOcupado;

    void arrancar() {
        if (activo) return;
        activo = true;
        avisadoOcupado = false;
        hilo = new Thread(new Runnable() {
            @Override public void run() { bucle(); }
        }, "polar-sondeo");
        hilo.setDaemon(true);
        hilo.start();
        Diario.info("OBD", "sondeo arrancado: " + PIDS.length + " PIDs por vuelta cada "
                + (ESPERA_VUELTA / 1000) + " s");
    }

    void parar() {
        activo = false;
        Thread t = hilo;
        hilo = null;
        if (t != null) t.interrupt();
        Diario.info("OBD", "sondeo parado");
    }

    private void bucle() {
        while (activo) {
            long pausa = unaVuelta();
            try {
                Thread.sleep(pausa);
            } catch (InterruptedException e) {
                return;      // nos han parado
            }
        }
    }

    /** Una vuelta completa: devuelve cuánto esperar antes de la siguiente. */
    private long unaVuelta() {
        if (BridgeService.enlaceOcupado()) {
            // El recolector está trabajando. No se toca el enlace; se espera. Se avisa
            // una sola vez por racha para no inundar el diario.
            if (!avisadoOcupado) {
                avisadoOcupado = true;
                Diario.info("OBD", "el recolector esta usando el adaptador: la pantalla espera su turno");
            }
            return ESPERA_OCUPADO;
        }
        if (avisadoOcupado) {
            avisadoOcupado = false;
            Diario.info("OBD", "adaptador libre: la pantalla vuelve a preguntar");
        }

        int obtenidos = 0;
        for (int i = 0; i < PIDS.length && activo; i++) {
            String pid = PIDS[i];
            String respuesta = BridgeService.preguntar("01" + pid, TIMEOUT_PID);
            if (respuesta == null) continue;      // ocupado o sin respuesta: no se inventa
            Double v = ObdParse.parse(respuesta, pid);
            if (v == null) continue;
            aplicar(pid, v);
            obtenidos++;
        }
        if (obtenidos == 0) {
            // Ni un dato en una vuelta entera: se dice, porque es el sintoma de que algo
            // va mal en la cadena (adaptador, contacto, ELM), y no se dice más de una vez
            // cada mucho.
            Diario.aviso("OBD", "una vuelta sin ningun dato del ELM");
        }
        return ESPERA_VUELTA;
    }

    /** Escribe el valor en el estado compartido, CON su sello de tiempo. */
    private void aplicar(String pid, Double v) {
        LiveState st = LiveState.INSTANCIA;
        if ("0D".equals(pid)) st.ponVelocidad(v);
        else if ("0C".equals(pid)) st.ponRegimen(v);
        else if ("05".equals(pid)) st.ponRefrigerante(v);
        else if ("04".equals(pid)) st.ponCarga(v);
        else if ("2F".equals(pid)) st.ponCombustible(v);
        else if ("42".equals(pid)) st.ponVoltaje(v);
        st.touch();
    }
}
