package com.cassiopeia.vgatebridge;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * El ordenador de viaje: VIAJE ACTUAL · HOY · TOTAL.
 *
 * Va aparte del dashboard a propósito. Un ordenador de viaje no se conduce mirándolo:
 * se consulta parado. Meterlo en la pantalla principal sería exactamente lo contrario
 * de la regla de esta pantalla, que es entenderse en menos de un segundo.
 *
 * Los tres ámbitos están separados porque son cosas distintas y mezclarlas engaña: el
 * viaje actual es lo que llevas desde que arrancaste, HOY es el día natural —que puede
 * incluir varios viajes— y el TOTAL es todo lo acumulado desde que se instaló.
 *
 * Todo sale de la velocidad real del ECU, no del GPS. Donde no hay dato, se dice "—":
 * una media sin muestras se declara no disponible en vez de enseñar un cero.
 */
public class ViajeActivity extends Activity {

    private final Handler h = new Handler();
    private LinearLayout columna;

    private final Runnable ciclo = new Runnable() {
        @Override public void run() {
            try { pintar(); } catch (Throwable t) {
                Diario.error("Viaje", "fallo al pintar el ordenador de viaje", t);
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
        Diario.info("Viaje", "abierto el ordenador de viaje");

        Paleta pal = Paleta.noche();
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setBackgroundColor(pal.fondo);
        columna = new LinearLayout(this);
        columna.setOrientation(LinearLayout.VERTICAL);
        columna.setPadding(dp(18), dp(14), dp(18), dp(18));
        scroll.addView(columna);
        setContentView(scroll);

        TextView t = new TextView(this);
        t.setText("Ordenador de viaje");
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(pal.acento);
        columna.addView(t);

        TextView origen = new TextView(this);
        origen.setText("Datos de la velocidad real del coche (OBD), no del GPS");
        origen.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        origen.setTextColor(pal.sec);
        columna.addView(origen);

        // El botón de reiniciar, grande: se pulsa parado, pero sin puntería fina.
        TextView boton = new TextView(this);
        boton.setText("Reiniciar el viaje actual");
        boton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        boton.setGravity(Gravity.CENTER);
        boton.setTypeface(Typeface.DEFAULT_BOLD);
        boton.setTextColor(pal.acento);
        boton.setBackground(new MusicaFranja.FondoRedondeado(pal.marca, dp(8), pal.borde));
        LinearLayout.LayoutParams lpB = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        lpB.topMargin = dp(10);
        boton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Viajes.INSTANCIA.reiniciarViaje();
                pintar();
            }
        });
        columna.addView(boton, lpB);

        pintar();
    }

    @Override protected void onResume() {
        super.onResume();
        h.removeCallbacks(ciclo);
        h.post(ciclo);
    }

    @Override protected void onPause() {
        super.onPause();
        h.removeCallbacks(ciclo);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        h.removeCallbacksAndMessages(null);
    }

    private void pintar() {
        Paleta pal = Paleta.noche();
        // Se reconstruye la tabla pero se dejan la cabecera, el origen y el botón.
        while (columna.getChildCount() > 3) columna.removeViewAt(3);

        bloque("VIAJE ACTUAL", Viajes.INSTANCIA.actual, pal);
        bloque("HOY", Viajes.INSTANCIA.hoy, pal);
        bloque("TOTAL DESDE LA INSTALACIÓN", Viajes.INSTANCIA.total, pal);

        Pizarra.anota("Viaje (actual)", Viaje.num(Viajes.INSTANCIA.actual.distanciaKm, 2, "km"));
        Pizarra.anota("Viaje (hoy)", Viaje.num(Viajes.INSTANCIA.hoy.distanciaKm, 2, "km"));
        Pizarra.anota("Viaje (total)", Viaje.num(Viajes.INSTANCIA.total.distanciaKm, 1, "km"));
    }

    /** Un ámbito, con sus cifras y la fuente de cada una. */
    private void bloque(String titulo, Viaje v, Paleta pal) {
        TextView t = new TextView(this);
        t.setText(titulo);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(pal.acento);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        columna.addView(t, lp);

        if (v.vacio()) {
            TextView vacio = new TextView(this);
            vacio.setText("Sin datos todavía: no se enseña un cero por un dato que no hay");
            vacio.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            vacio.setTextColor(pal.apagado);
            columna.addView(vacio);
            return;
        }

        fila("Distancia", Viaje.num(v.distanciaKm, 2, "km"), "OBD · integral de la velocidad", pal);
        fila("Tiempo con el motor en marcha", v.tiempoMotor(), "OBD/Vgate", pal);
        fila("Velocidad media", Viaje.num(v.velocidadMedia(), 1, "km/h"), "distancia / tiempo en movimiento", pal);
        fila("Velocidad máxima", Viaje.num(v.velMax, 0, "km/h"), "máximo visto", pal);
        fila("Consumo medio", Viaje.num(v.consumoMedio(), 2, "L/100km"), "CAN · ponderado por distancia", pal);
        fila("Combustible usado", Viaje.num(v.litros, 2, "L"), "calculado", pal);
        fila("Régimen medio / máximo", Viaje.num(v.regimenMedio(), 0, "") + " / " + Viaje.num(v.rpmMax, 0, "rpm"),
                "OBD", pal);
        fila("Temperatura mín. / máx.", Viaje.num(v.tempMin, 0, "°C") + " / " + Viaje.num(v.tempMax, 0, "°C"),
                "OBD", pal);
        fila("Muestras válidas", v.muestrasVel + " de velocidad, " + v.muestrasRpm + " de régimen",
                "si son 0, las medias no existen", pal);
    }

    private void fila(String nombre, String valor, String nota, Paleta pal) {
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.HORIZONTAL);
        f.setGravity(Gravity.CENTER_VERTICAL);
        f.setPadding(0, dp(3), 0, dp(3));

        TextView n = new TextView(this);
        n.setText(nombre);
        n.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        n.setTextColor(pal.sec);
        f.addView(n, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f));

        TextView v = new TextView(this);
        v.setText(valor);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setTextColor(pal.acento);
        f.addView(v, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView p = new TextView(this);
        p.setText(nota);
        p.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        p.setTextColor(pal.apagado);
        f.addView(p, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1.3f));

        columna.addView(f);
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
