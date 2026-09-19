package com.cassiopeia.vgatebridge;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

/**
 * Un instrumento de cuadro: aro, escala, zona roja, aguja y valor.
 *
 * Está dibujado a mano con Canvas, sin XML ni recursos. Tres decisiones que importan:
 *
 *  - La aguja se ANIMA por interpolación hacia el valor nuevo, no salta. Un salto en
 *    un velocímetro se lee como una avería; el movimiento continuo se lee como el
 *    coche. La animación la mueve el propio onDraw y se detiene sola cuando llega, así
 *    que no hay animadores vivos consumiendo CPU en un SoC flojo.
 *  - Un valor DESCONOCIDO (null) pinta una raya donde iría el número y deja la aguja
 *    en el mínimo. Nunca un cero: cero es un dato, "no lo sé" es otra cosa, y
 *    confundirlos en un cuadro es mentir al conductor.
 *  - La zona roja se dibuja solo si hay umbral, y va POR DEBAJO de la aguja: el color
 *    de aviso aparece cuando el valor entra en ella, no antes.
 */
final class InstrumentoView extends View {

    /** Desde dónde y cuánto barre la escala: 240°, con el hueco abajo. */
    private static final float INICIO = 150f;
    private static final float BARRIDO = 240f;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF caja = new RectF();
    private final Path aguja = new Path();

    private String etiqueta;
    private String unidad;
    private double minimo;
    private double maximo;
    private Double umbralRojo;       // null = este instrumento no tiene zona roja
    private boolean sinUmbralBajo;  // true = la zona roja es por arriba (régimen, temperatura)

    private Double objetivo;         // el valor que llega del coche (null = desconocido)
    private double mostrado = Double.NaN;

    private Paleta pal;
    private int cifras = 0;
    private boolean grande;          // el instrumento principal se dibuja más grueso

    InstrumentoView(Context ctx, Paleta pal, String etiqueta, String unidad,
                    double minimo, double maximo, Double umbralRojo, boolean rojoArriba,
                    boolean principal) {
        super(ctx);
        this.pal = pal;
        this.etiqueta = etiqueta;
        this.unidad = unidad;
        this.minimo = minimo;
        this.maximo = maximo;
        this.umbralRojo = umbralRojo;
        this.sinUmbralBajo = rojoArriba;
        this.grande = principal;
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
    }

    /** Cambia la paleta cuando el coche enciende o apaga las luces. */
    void paleta(Paleta nueva) {
        this.pal = nueva;
        invalidate();
    }

    /** El valor que llega del coche. null significa "no lo sé", no cero. */
    void valor(Double v) {
        if (v != null && (v.isNaN() || v.isInfinite())) v = null;
        objetivo = v;
        if (mostrado == Double.NaN || Double.isNaN(mostrado)) {
            mostrado = (v == null) ? minimo : v.doubleValue();   // el primero no se anima
            invalidate();
            return;
        }
        postInvalidateOnAnimation();     // arranca la animación hacia el valor nuevo
    }

    /** El valor que se está mostrando ahora mismo (para las comprobaciones). */
    Double valorMostrado() {
        return objetivo;
    }

    @Override
    protected void onDraw(Canvas cv) {
        super.onDraw(cv);
        if (pal == null) return;

        // ── La aguja camina hacia el objetivo, sin saltar ────────────────────
        double destino = (objetivo == null) ? minimo : objetivo.doubleValue();
        double falta = destino - mostrado;
        boolean animando = Math.abs(falta) > 0.01;
        if (animando) {
            mostrado += falta * 0.22;      // interpolación: llegada suave en ~10 fotogramas
            if (Math.abs(destino - mostrado) < 0.01) { mostrado = destino; animando = false; }
        }

        int w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f;
        float grosorAro = grande ? dp(7) : dp(5);
        float radio = Math.min(w, h) / 2f - grosorAro - dp(4);
        float centroValor = grande ? cy + dp(6) : cy + dp(2);
        radio = Math.max(radio, dp(12));
        caja.set(cx - radio, cy - radio, cx + radio, cy + radio);

        // ── Aro de fondo ─────────────────────────────────────────────────────
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(grosorAro);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(pal.aro);
        cv.drawArc(caja, INICIO, BARRIDO, false, p);

        // ── Zona roja, por debajo de la aguja ────────────────────────────────
        if (umbralRojo != null) {
            double u = umbralRojo.doubleValue();
            float desde, hasta;
            if (sinUmbralBajo) {                       // aviso por arriba
                desde = fraccion(u); hasta = 1f;
            } else {                                   // aviso por abajo (combustible)
                desde = 0f; hasta = fraccion(u);
            }
            if (hasta > desde) {
                p.setColor((pal.alerta & 0x00FFFFFF) | 0x55000000);   // alerta translúcida
                cv.drawArc(caja, INICIO + BARRIDO * desde, BARRIDO * (hasta - desde), false, p);
            }
        }

        // ── Marcas de la escala ──────────────────────────────────────────────
        int divisiones = grande ? 6 : 4;
        p.setStrokeCap(Paint.Cap.BUTT);
        for (int i = 0; i <= divisiones; i++) {
            float f = (float) i / divisiones;
            double ang = Math.toRadians(INICIO + BARRIDO * f);
            boolean mayor = (i % 2 == 0) || grande;
            float largo = mayor ? dp(9) : dp(5);
            float r1 = radio - grosorAro / 2f - dp(2);
            float r2 = r1 - largo;
            p.setStrokeWidth(mayor ? dp(2) : dp(1));
            p.setColor(mayor ? pal.marca : pal.apagado);
            cv.drawLine((float) (cx + Math.cos(ang) * r1), (float) (cy + Math.sin(ang) * r1),
                        (float) (cx + Math.cos(ang) * r2), (float) (cy + Math.sin(ang) * r2), p);
        }

        // ── Los números de la escala ─────────────────────────────────────────
        // Sin números, una aguja no se puede leer: se sabe que apunta "a la mitad",
        // no a cuánto. Van por dentro del aro, que es donde se miran.
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(grande ? dp(11) : dp(9));
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        p.setColor(pal.sec);
        p.setStyle(Paint.Style.FILL);
        for (int i = 0; i <= divisiones; i += 2) {
            double v = minimo + (maximo - minimo) * ((double) i / divisiones);
            double a = Math.toRadians(INICIO + BARRIDO * ((float) i / divisiones));
            float rt = radio - grosorAro - dp(grande ? 17 : 13);
            cv.drawText(textoEscala(v),
                    (float) (cx + Math.cos(a) * rt),
                    (float) (cy + Math.sin(a) * rt) + dp(3), p);
        }

        // ── Arco de valor hasta la posición actual ───────────────────────────
        float f = fraccion(mostrado);
        if (f > 0f) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(grosorAro);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(colorValor());
            cv.drawArc(caja, INICIO, BARRIDO * f, false, p);
        }

        // ── La aguja ─────────────────────────────────────────────────────────
        double ang = Math.toRadians(INICIO + BARRIDO * f);
        float largoAguja = radio - grosorAro - dp(6);
        float bx = (float) (cx - Math.cos(ang) * dp(6));
        float by = (float) (cy - Math.sin(ang) * dp(6));
        float px = (float) (cx + Math.cos(ang) * largoAguja);
        float py = (float) (cy + Math.sin(ang) * largoAguja);
        // Perpendicular, para dar anchura a la aguja en la base y punta fina.
        float nx = (float) -Math.sin(ang), ny = (float) Math.cos(ang);
        float ancho = grande ? dp(2.6f) : dp(2f);
        aguja.reset();
        aguja.moveTo(bx + nx * ancho, by + ny * ancho);
        aguja.lineTo(px, py);
        aguja.lineTo(bx - nx * ancho, by - ny * ancho);
        aguja.close();
        p.setStyle(Paint.Style.FILL);
        p.setColor(colorValor());
        cv.drawPath(aguja, p);

        // Eje de la aguja
        p.setColor(pal.acento);
        cv.drawCircle(cx, cy, grande ? dp(4) : dp(3), p);

        // ── El número ────────────────────────────────────────────────────────
        p.setTextAlign(Paint.Align.CENTER);
        p.setColor(objetivo == null ? pal.apagado : pal.acento);
        p.setTextSize(grande ? dp(46) : dp(24));
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        String texto = (objetivo == null) ? "—" : formatea(mostrado);
        cv.drawText(texto, cx, centroValor + dp(2), p);

        // ── La unidad ────────────────────────────────────────────────────────
        p.setColor(pal.sec);
        p.setTextSize(grande ? dp(13) : dp(10));
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        cv.drawText(unidad, cx, centroValor + (grande ? dp(20) : dp(14)), p);

        // ── La etiqueta, debajo del instrumento ──────────────────────────────
        p.setColor(pal.sec);
        p.setTextSize(grande ? dp(12) : dp(10));
        cv.drawText(etiqueta, cx, cy + radio + grosorAro + (grande ? dp(4) : dp(2)), p);

        // Para las comprobaciones automáticas y para quien use lector de pantalla:
        // el valor también existe como texto del propio View.
        setContentDescription(etiqueta + ": " + texto + " " + unidad);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);

        // Y aquí queda constancia de lo DIBUJADO, que es lo que se comprueba.
        Pizarra.anota(etiqueta, texto);

        if (animando) postInvalidateOnAnimation();
    }

    /**
     * Cómo se escribe un número de la escala.
     *
     * Se decide por el RANGO, no por el instrumento: un rango de 6 (el régimen, en
     * miles) quiere enteros, y uno de 0,8 querría un decimal. Así el mismo
     * instrumento sirve para cualquiera de los tres sin casos especiales.
     */
    private String textoEscala(double v) {
        double rango = maximo - minimo;
        if (rango >= 1) return String.valueOf((long) Math.round(v));
        return String.format(java.util.Locale.US, "%.1f", v);
    }

    /** Posición en la escala, de 0 a 1, sin salirse. */
    private float fraccion(double v) {
        if (maximo <= minimo) return 0f;
        float f = (float) ((v - minimo) / (maximo - minimo));
        if (f < 0f) f = 0f;
        if (f > 1f) f = 1f;
        return f;
    }

    /** El color del valor: de aviso si está en zona roja, normal si no. */
    private int colorValor() {
        if (objetivo == null) return pal.aro;
        double v = objetivo.doubleValue();
        if (umbralRojo != null) {
            double u = umbralRojo.doubleValue();
            if (sinUmbralBajo ? (v >= u) : (v <= u)) return pal.alerta;
        }
        return pal.acento;
    }

    /** El número, con los decimales que se hayan pedido. */
    private String formatea(double v) {
        if (cifras <= 0) return String.valueOf((long) Math.round(v));
        return String.format(java.util.Locale.US, "%." + cifras + "f", v);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (Double.isNaN(mostrado)) mostrado = minimo;
    }

    // Constructor con atributos, por si algún día se usa desde XML.
    InstrumentoView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
    }
}
