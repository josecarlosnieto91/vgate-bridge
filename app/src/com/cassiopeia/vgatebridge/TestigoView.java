package com.cassiopeia.vgatebridge;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.view.View;

/**
 * Un testigo del cuadro: el dibujo y el texto de un aviso.
 *
 * Se muestran SOLO cuando ocurren, nunca apagados en pantalla. Un testigo siempre
 * encendido no avisa de nada: se convierte en decoración que el ojo aprende a
 * ignorar, y el día que de verdad importa ya no se ve. Por eso este View existe
 * únicamente mientras el aviso está activo.
 *
 * Los dibujos son formas simples trazadas a mano: no hay recursos gráficos que
 * mantener ni imágenes que escalar en un SoC flojo.
 */
final class TestigoView extends View {

    static final int PUERTA = 0;
    static final int LUCES = 1;
    static final int AVISO = 2;
    static final int MOTOR = 3;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path trazo = new Path();

    private final int tipo;
    private final String texto;
    private Paleta pal;

    TestigoView(Context ctx, Paleta pal, int tipo, String texto) {
        super(ctx);
        this.pal = pal;
        this.tipo = tipo;
        this.texto = texto;
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
    }

    void paleta(Paleta nueva) {
        this.pal = nueva;
        invalidate();
    }

    @Override
    protected void onMeasure(int ancho, int alto) {
        // Ancho del icono + el texto, alto fijo: cabe en la tira superior.
        int icono = (int) dp(18);
        float anchoTexto = texto == null ? 0 : p.measureText(texto) + dp(10);
        setMeasuredDimension((int) (icono + anchoTexto + dp(14)), (int) dp(24));
    }

    @Override
    protected void onDraw(Canvas cv) {
        super.onDraw(cv);
        if (pal == null) return;
        float cx = dp(9), cy = getHeight() / 2f;
        float r = dp(8);
        int color = (tipo == LUCES) ? pal.verde : pal.alerta;

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(1.6f));
        p.setColor(color);
        trazo.reset();

        if (tipo == PUERTA) {
            // Una puerta entreabierta: un rectángulo abierto y la hoja girada.
            cv.drawRect(cx - r, cy - r, cx - r + dp(4), cy + r, p);
            trazo.moveTo(cx - r + dp(4), cy - r);
            trazo.lineTo(cx + r, cy - r * 0.6f);
            trazo.lineTo(cx + r, cy + r * 0.6f);
            trazo.lineTo(cx - r + dp(4), cy + r);
            trazo.close();
            p.setStyle(Paint.Style.FILL);
            cv.drawPath(trazo, p);
        } else if (tipo == LUCES) {
            // Un faro: el cuerpo y tres rayos.
            cv.drawCircle(cx - dp(1), cy, dp(4.5f), p);
            p.setStrokeWidth(dp(1.4f));
            for (int i = -1; i <= 1; i++) {
                float a = (float) Math.toRadians(-30 + i * 30);
                cv.drawLine(cx - dp(1) + (float) Math.cos(a) * dp(6), cy + (float) Math.sin(a) * dp(6),
                            cx - dp(1) + (float) Math.cos(a) * dp(9), cy + (float) Math.sin(a) * dp(9), p);
            }
        } else if (tipo == MOTOR) {
            // Bloque de motor estilizado.
            cv.drawRect(cx - r * 0.9f, cy - r * 0.5f, cx + r * 0.9f, cy + r * 0.7f, p);
            cv.drawRect(cx - r * 0.3f, cy - r * 0.9f, cx + r * 0.3f, cy - r * 0.5f, p);
        } else {
            // Aviso genérico: triángulo con el signo dentro.
            trazo.moveTo(cx, cy - r);
            trazo.lineTo(cx + r, cy + r * 0.8f);
            trazo.lineTo(cx - r, cy + r * 0.8f);
            trazo.close();
            cv.drawPath(trazo, p);
            p.setStyle(Paint.Style.FILL);
            cv.drawRect(cx - dp(0.9f), cy - dp(2), cx + dp(0.9f), cy + dp(1.4f), p);
            cv.drawCircle(cx, cy + dp(3.4f), dp(1), p);
        }

        // ── El texto del aviso ───────────────────────────────────────────────
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        p.setTextAlign(Paint.Align.LEFT);
        p.setTextSize(dp(12));
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        cv.drawText(texto, dp(22), cy + dp(4), p);

        setContentDescription(texto);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
