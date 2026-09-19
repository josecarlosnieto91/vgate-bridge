package com.cassiopeia.vgatebridge;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/**
 * Barra de nivel: combustible y autonomía.
 *
 * El combustible no merece una aguja. Baja despacio, en cuestión de días, así que un
 * arco que hay que interpretar es peor que una barra que se lee de un vistazo: llena
 * o vacía. La aguja se reserva para lo que cambia rápido y hay que mirar de reojo.
 *
 * Debajo del nivel va la autonomía en kilómetros, que es el dato que de verdad se
 * quiere: a nadie le importa el porcentaje, importan los kilómetros que quedan.
 */
final class BarraView extends View {

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF raiz = new RectF();

    private String etiqueta;
    private String unidad;
    private Double umbralBajo;        // % por debajo del cual la barra avisa
    private Paleta pal;

    private Double nivel;            // % (null = desconocido)
    private Double secundario;       // autonomía en km (null = desconocido)
    private double mostrado = Double.NaN;

    BarraView(Context ctx, Paleta pal, String etiqueta, String unidad, Double umbralBajo) {
        super(ctx);
        this.pal = pal;
        this.etiqueta = etiqueta;
        this.unidad = unidad;
        this.umbralBajo = umbralBajo;
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
    }

    void paleta(Paleta nueva) {
        this.pal = nueva;
        invalidate();
    }

    /** Nivel en porcentaje de 0 a 100. null = desconocido. */
    void nivel(Double pct) {
        if (pct != null && (pct.isNaN() || pct.isInfinite())) pct = null;
        nivel = pct;
        if (Double.isNaN(mostrado)) {
            mostrado = (pct == null) ? 0 : pct.doubleValue();
            invalidate();
            return;
        }
        postInvalidateOnAnimation();
    }

    /** Dato secundario: la autonomía, en kilómetros. */
    void secundario(Double km) {
        this.secundario = (km != null && (km.isNaN() || km.isInfinite())) ? null : km;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas cv) {
        super.onDraw(cv);
        if (pal == null) return;

        int w = getWidth(), h = getHeight();
        float margen = dp(2);

        // ── La etiqueta ──────────────────────────────────────────────────────
        p.setTextAlign(Paint.Align.LEFT);
        p.setTextSize(dp(10));
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        p.setColor(pal.sec);
        cv.drawText(etiqueta.toUpperCase(), margen, dp(11), p);

        // ── El nivel, a la derecha ───────────────────────────────────────────
        p.setTextAlign(Paint.Align.RIGHT);
        p.setTextSize(dp(15));
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        float altoEtiqueta = dp(16);
        boolean bajo = nivel != null && umbralBajo != null
                && nivel.doubleValue() <= umbralBajo.doubleValue();
        p.setColor(nivel == null ? pal.apagado : (bajo ? pal.alerta : pal.acento));
        cv.drawText(nivel == null ? "—" : String.valueOf((long) Math.round(nivel.doubleValue())),
                w - margen, dp(15), p);

        // ── La barra ─────────────────────────────────────────────────────────
        float altoBarra = dp(11);
        float y = altoEtiqueta + dp(3);
        raiz.set(margen, y, w - margen, y + altoBarra);
        float radio = altoBarra / 2f;

        p.setStyle(Paint.Style.FILL);
        p.setColor(pal.aro);
        cv.drawRoundRect(raiz, radio, radio, p);

        double destino = (nivel == null) ? 0 : nivel.doubleValue();
        double falta = destino - (Double.isNaN(mostrado) ? destino : mostrado);
        boolean animando = Math.abs(falta) > 0.05;
        if (animando) {
            mostrado += falta * 0.25;
            if (Math.abs(destino - mostrado) < 0.05) { mostrado = destino; animando = false; }
        }
        float f = (float) Math.max(0, Math.min(100, Double.isNaN(mostrado) ? destino : mostrado)) / 100f;
        if (f > 0.005f) {
            // El relleno nunca baja del radio, para que la barra no parezca un cuadrado.
            RectF relleno = new RectF(margen, y, margen + Math.max(altoBarra, (w - 2 * margen) * f), y + altoBarra);
            p.setColor(bajo ? pal.alerta : pal.acento);
            cv.drawRoundRect(relleno, radio, radio, p);
        }

        // ── La autonomía, debajo ─────────────────────────────────────────────
        p.setTextAlign(Paint.Align.LEFT);
        p.setTextSize(dp(12));
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        p.setColor(pal.sec);
        String resto = (secundario == null) ? "—" : String.valueOf((long) Math.round(secundario.doubleValue())) + " " + unidad;
        cv.drawText(resto, margen, y + altoBarra + dp(14), p);

        setContentDescription(etiqueta + ": "
                + (nivel == null ? "—" : String.valueOf((long) Math.round(nivel.doubleValue())) + " %")
                + ", " + resto);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        Pizarra.anota(etiqueta, nivel == null ? "—" : String.valueOf((long) Math.round(nivel.doubleValue())));
        Pizarra.anota(etiqueta + " autonomía", secundario == null ? "—"
                : String.valueOf((long) Math.round(secundario.doubleValue())));
        if (animando) postInvalidateOnAnimation();
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
