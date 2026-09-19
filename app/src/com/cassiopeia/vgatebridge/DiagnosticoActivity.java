package com.cassiopeia.vgatebridge;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * La pantalla de diagnóstico del coche.
 *
 * Es la que ningún launcher de tienda puede hacer: no solo enseña el dato, enseña DE
 * DÓNDE VIENE, CUÁNDO llegó y SI SIGUE VALIENDO. Eso es exactamente lo que hace falta
 * para depurar la cadena Vgate → ELM327 → CAN, y para saber si un instrumento está
 * parado porque el coche no habla o porque nosotros no preguntamos.
 *
 * Tres estados por lectura, siempre distinguidos:
 *   ACTUAL   el dato es de ahora mismo
 *   CADUCADO llegó, pero hace más de lo permitido: NO se puede enseñar como actual
 *   SIN DATO  nunca ha llegado
 *
 * Los umbrales de caducidad son distintos por magnitud, y no por capricho: el régimen
 * cambia cien veces por segundo y la temperatura exterior cada varios minutos. Aplicar
 * el mismo plazo a todo daría por muerto un dato bueno o por vivo uno viejo.
 */
public class DiagnosticoActivity extends Activity {

    /** Plazos de caducidad, en milisegundos, por magnitud. */
    private static final long CADUCA_RAPIDO = 3000;      // rpm, velocidad, carga
    private static final long CADUCA_MEDIO = 10000;      // refrigerante, consumo
    private static final long CADUCA_LENTO = 120000;     // exterior, combustible, autonomía
    private static final long CADUCA_ESTADO = 15000;     // puertas, luces

    private final LiveState st = LiveState.INSTANCIA;
    private final Handler h = new Handler();

    private LinearLayout columna;
    private TextView estadoObd, estadoCan, pie;

    private final Runnable ciclo = new Runnable() {
        @Override public void run() {
            try {
                pintar();
            } catch (Throwable t) {
                Diario.error("Diagnostico", "fallo al pintar el diagnostico", t);
            }
            h.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle estado) {
        super.onCreate(estado);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        Diario.info("Diagnostico", "abierta la pantalla de diagnostico");

        Paleta pal = Paleta.noche();
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(pal.fondo);

        columna = new LinearLayout(this);
        columna.setOrientation(LinearLayout.VERTICAL);
        columna.setPadding(dp(18), dp(14), dp(18), dp(18));
        scroll.addView(columna);

        TextView t = new TextView(this);
        t.setText("Diagnóstico del coche");
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(pal.acento);
        columna.addView(t);

        estadoObd = new TextView(this);
        estadoObd.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        estadoObd.setTypeface(Typeface.DEFAULT_BOLD);
        columna.addView(estadoObd);

        estadoCan = new TextView(this);
        estadoCan.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        estadoCan.setTypeface(Typeface.DEFAULT_BOLD);
        columna.addView(estadoCan);

        pie = new TextView(this);
        pie.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        pie.setTextColor(pal.sec);
        LinearLayout.LayoutParams lpPie = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lpPie.topMargin = dp(10);
        columna.addView(pie, lpPie);

        setContentView(scroll);
        pintar();
    }

    @Override
    protected void onResume() {
        super.onResume();
        h.removeCallbacks(ciclo);
        h.post(ciclo);
    }

    @Override
    protected void onPause() {
        super.onPause();
        h.removeCallbacks(ciclo);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        h.removeCallbacksAndMessages(null);
    }

    /** Reconstruye la tabla. Es una pantalla de diagnóstico: se puede permitir repintar. */
    private void pintar() {
        Paleta pal = Paleta.noche();

        // Fuera todo menos la cabecera (los tres primeros hijos).
        while (columna.getChildCount() > 3) columna.removeViewAt(3);

        // ── Estado de las dos vías ───────────────────────────────────────────
        long edadObd = edadMasReciente(st.speedKmhAt, st.rpmAt, st.coolantCAt, st.loadPctAt,
                st.batteryVAt, st.consumptionL100At, st.rangeKmAt, st.fuelLevelPctAt);
        long edadCan = edadMasReciente(st.outsideTempCAt, st.doorOpenAt, st.lightsOnAt);

        estadoObd.setText("OBD/Vgate: " + etiquetaVia(edadObd, CADUCA_MEDIO));
        estadoObd.setTextColor(colorVia(edadObd, CADUCA_MEDIO, pal));
        estadoCan.setText("CAN (unidad): " + etiquetaVia(edadCan, CADUCA_ESTADO));
        estadoCan.setTextColor(colorVia(edadCan, CADUCA_ESTADO, pal));

        // ── Una fila por magnitud ────────────────────────────────────────────
        fila("Velocidad", texto(st.speedKmh, " km/h"), LiveState.FUENTE_OBD, st.speedKmhAt, CADUCA_RAPIDO, pal);
        fila("Régimen", texto(st.rpm, " rpm"), LiveState.FUENTE_OBD, st.rpmAt, CADUCA_RAPIDO, pal);
        fila("Refrigerante", texto(st.coolantC, " °C"), LiveState.FUENTE_OBD, st.coolantCAt, CADUCA_MEDIO, pal);
        fila("Carga del motor", texto(st.loadPct, " %"), LiveState.FUENTE_OBD, st.loadPctAt, CADUCA_RAPIDO, pal);
        fila("Voltaje", texto(st.batteryV, " V"), LiveState.FUENTE_OBD, st.batteryVAt, CADUCA_MEDIO, pal);
        fila("Consumo", texto(st.consumptionL100, " L/100km"), LiveState.FUENTE_CAN, st.consumptionL100At, CADUCA_MEDIO, pal);
        fila("Autonomía", texto(st.rangeKm, " km"), LiveState.FUENTE_CAN, st.rangeKmAt, CADUCA_LENTO, pal);
        fila("Combustible", texto(st.fuelLevelPct, " %"), LiveState.FUENTE_CAN, st.fuelLevelPctAt, CADUCA_LENTO, pal);
        fila("Temperatura exterior", st.outsideTempC == null ? "—" : st.outsideTempC + " °C",
                LiveState.FUENTE_CAN, st.outsideTempCAt, CADUCA_LENTO, pal);
        fila("Puertas", estadoBool(st.doorOpen), LiveState.FUENTE_CAN, st.doorOpenAt, CADUCA_ESTADO, pal);
        fila("Alumbrado", estadoBool(st.lightsOn), LiveState.FUENTE_CAN, st.lightsOnAt, CADUCA_ESTADO, pal);

        // ── Los errores recientes, al final ─────────────────────────────────
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        t.setTextColor(pal.sec);
        t.setText("Últimos avisos del diario:\n" + Diario.texto());
        columna.addView(t);

        pie.setText("Los datos de prueba, si existen, se ven aquí igual: por eso cada "
                + "lectura lleva su fuente y su edad. Un valor con edad no engaña.");
    }

    /** Una fila: nombre, valor, fuente, edad y estado. */
    private void fila(String nombre, String valor, String fuente, long sello,
                      long caducaMs, Paleta pal) {
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.HORIZONTAL);
        f.setGravity(Gravity.CENTER_VERTICAL);
        f.setPadding(0, dp(4), 0, dp(4));

        TextView n = new TextView(this);
        n.setText(nombre);
        n.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        n.setTextColor(pal.sec);
        f.addView(n, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f));

        TextView v = new TextView(this);
        v.setText(valor);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        int estado = st.estado(sello, caducaMs);
        // El valor de un dato caducado se enseña APAGADO y con su edad: no se esconde,
        // porque es información útil, pero no se disfraza de dato actual.
        v.setTextColor(estado == LiveState.ACTUAL ? pal.acento : pal.apagado);
        f.addView(v, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1.1f));

        TextView e = new TextView(this);
        String etiqueta = fuente + " · " + LiveState.comoEdad(st.edad(sello));
        if (estado == LiveState.CADUCADO) etiqueta = fuente + " · CADUCADO (" + LiveState.comoEdad(st.edad(sello)) + ")";
        if (estado == LiveState.SIN_DATO) etiqueta = fuente + " · sin dato";
        e.setText(etiqueta);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        e.setTextColor(estado == LiveState.ACTUAL ? pal.verde
                : (estado == LiveState.CADUCADO ? pal.alerta : pal.apagado));
        f.addView(e, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1.5f));

        columna.addView(f);
    }

    private static long edadMasReciente(long... sellos) {
        long mejor = -1;
        for (int i = 0; i < sellos.length; i++) {
            if (sellos[i] == 0L) continue;
            long edad = System.currentTimeMillis() - sellos[i];
            if (mejor < 0 || edad < mejor) mejor = edad;
        }
        return mejor;
    }

    private static String etiquetaVia(long edad, long caducaMs) {
        if (edad < 0) return "SIN DATOS";
        if (edad > caducaMs) return "SIN DATOS (" + LiveState.comoEdad(edad) + ")";
        return "CONECTADO (" + LiveState.comoEdad(edad) + ")";
    }

    private static int colorVia(long edad, long caducaMs, Paleta pal) {
        if (edad < 0) return pal.apagado;
        return edad > caducaMs ? pal.alerta : pal.verde;
    }

    private static String texto(Double v, String unidad) {
        if (v == null) return "—";
        return String.format(java.util.Locale.US, "%.1f", v.doubleValue()) + unidad;
    }

    private static String estadoBool(Boolean v) {
        if (v == null) return "—";
        return v.booleanValue() ? "sí" : "no";
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
