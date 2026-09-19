package com.cassiopeia.vgatebridge;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * La música, en una franja compacta.
 *
 * Antes ocupaba una tarjeta enorme con carátula, tres líneas de texto y controles
 * grandes. Ahora la zona grande es para la navegación, que es lo que se mira
 * conduciendo, y la música queda reducida a lo justo: carátula, canción, artista y
 * los tres controles.
 *
 * Los controles siguen midiendo 44 puntos de alto: compacto no significa difícil de
 * pulsar. Lo que se quita es espacio, no accesibilidad.
 *
 * La información sale de MediaSession, así que funciona con Spotify y con cualquier
 * otro reproductor que publique su sesión. Sin Internet para pintarla.
 */
final class MusicaFranja extends LinearLayout {

    private final Paleta pal;
    private final FrameLayout marco;
    private final TextView titulo;
    private final TextView artista;
    private final LinearLayout controles;

    private MediaSesion.Info ultima;
    private Bitmap caratula;
    private static boolean sonando;

    MusicaFranja(Context ctx, Paleta pal) {
        super(ctx);
        this.pal = pal;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setPadding((int) dp(10), (int) dp(6), (int) dp(10), (int) dp(6));
        setBackground(new FondoRedondeado(pal.sup, dp(10), pal.borde));

        marco = new FrameLayout(ctx) {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            protected void onDraw(Canvas cv) {
                float r = dp(6);
                RectF caja = new RectF(0, 0, getWidth(), getHeight());
                p.setColor(pal.marca);
                cv.drawRoundRect(caja, r, r, p);
                if (caratula != null) {
                    Path recorte = new Path();
                    recorte.addRoundRect(caja, r, r, Path.Direction.CW);
                    cv.save();
                    cv.clipPath(recorte);
                    cv.drawBitmap(caratula, null, caja, null);
                    cv.restore();
                } else {
                    p.setColor(pal.apagado);
                    float cx = getWidth() / 2f, cy = getHeight() / 2f;
                    cv.drawCircle(cx - dp(2), cy + dp(3), dp(2.4f), p);
                    cv.drawRect(cx, cy - dp(5), cx + dp(1.2f), cy + dp(3), p);
                }
            }
        };
        int lado = (int) dp(42);
        LinearLayout.LayoutParams lpMarco = new LinearLayout.LayoutParams(lado, lado);
        lpMarco.rightMargin = (int) dp(10);
        addView(marco, lpMarco);

        LinearLayout textos = new LinearLayout(ctx);
        textos.setOrientation(VERTICAL);
        textos.setGravity(Gravity.CENTER_VERTICAL);
        addView(textos, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        titulo = new TextView(ctx);
        titulo.setTextSize(13);
        titulo.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        titulo.setSingleLine(true);
        titulo.setEllipsize(android.text.TextUtils.TruncateAt.END);
        textos.addView(titulo);

        artista = new TextView(ctx);
        artista.setTextSize(11);
        artista.setSingleLine(true);
        artista.setEllipsize(android.text.TextUtils.TruncateAt.END);
        textos.addView(artista);

        controles = new LinearLayout(ctx);
        controles.setOrientation(HORIZONTAL);
        controles.setGravity(Gravity.CENTER_VERTICAL);
        addView(controles, new LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));

        controles.addView(boton(Simbolo.ANTERIOR, new Runnable() {
            @Override public void run() { MediaSesion.control(getContext(), "anterior"); }
        }));
        controles.addView(boton(Simbolo.ALTERNAR, new Runnable() {
            @Override public void run() { MediaSesion.alternar(getContext()); }
        }));
        controles.addView(boton(Simbolo.SIGUIENTE, new Runnable() {
            @Override public void run() { MediaSesion.control(getContext(), "siguiente"); }
        }));

        pintar(new MediaSesion.Info());
    }

    void paleta(Paleta nueva) {
        setBackground(new FondoRedondeado(nueva.sup, dp(10), nueva.borde));
        invalidate();
    }

    void pintar(MediaSesion.Info info) {
        if (info == null) info = new MediaSesion.Info();
        if (ultima != null && ultima.permiso == info.permiso && ultima.sonando == info.sonando
                && igual(ultima.titulo, info.titulo) && igual(ultima.artista, info.artista)
                && ultima.caratula == info.caratula) {
            return;    // nada ha cambiado: no se toca la pantalla
        }
        ultima = info;
        sonando = info.sonando;
        caratula = info.caratula;
        marco.invalidate();

        if (!info.permiso) {
            titulo.setText("Sin acceso a las notificaciones");
            titulo.setTextColor(pal.apagado);
            artista.setText("Se concede desde el panel de navegación");
        } else if (info.titulo == null) {
            titulo.setText("Sin reproducción");
            titulo.setTextColor(pal.apagado);
            artista.setText("");
        } else {
            titulo.setText(info.titulo);
            titulo.setTextColor(pal.acento);
            artista.setText(info.artista == null ? "" : info.artista);
            artista.setTextColor(pal.sec);
        }
        controles.setVisibility(info.permiso ? VISIBLE : GONE);
        View central = controles.getChildAt(1);
        if (central != null) central.invalidate();

        setContentDescription("Música: " + (info.titulo == null ? "sin reproducción" : info.titulo));
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        Pizarra.anota("Música", info.permiso
                ? (info.titulo == null ? "sin reproducción" : info.titulo) : "sin permiso");
    }

    private static boolean igual(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private View boton(final int simbolo, final Runnable accion) {
        View v = new View(getContext()) {
            @Override
            protected void onDraw(Canvas cv) {
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setColor(pal.acento);
                float cx = getWidth() / 2f, cy = getHeight() / 2f;
                float r = 9 * cv.getDensity();
                if (simbolo == Simbolo.ANTERIOR || simbolo == Simbolo.SIGUIENTE) {
                    float s = (simbolo == Simbolo.SIGUIENTE) ? 1f : -1f;
                    Path t = new Path();
                    t.moveTo(cx - s * r * 0.7f, cy - r * 0.7f);
                    t.lineTo(cx + s * r * 0.5f, cy);
                    t.lineTo(cx - s * r * 0.7f, cy + r * 0.7f);
                    t.close();
                    cv.drawPath(t, p);
                } else if (sonando) {
                    p.setStrokeWidth(2.2f * cv.getDensity());
                    cv.drawLine(cx - r * 0.3f, cy - r * 0.65f, cx - r * 0.3f, cy + r * 0.65f, p);
                    cv.drawLine(cx + r * 0.3f, cy - r * 0.65f, cx + r * 0.3f, cy + r * 0.65f, p);
                } else {
                    Path t = new Path();
                    t.moveTo(cx - r * 0.4f, cy - r * 0.7f);
                    t.lineTo(cx + r * 0.6f, cy);
                    t.lineTo(cx - r * 0.4f, cy + r * 0.7f);
                    t.close();
                    cv.drawPath(t, p);
                }
            }
        };
        // 44 puntos de ancho por 44 de alto: se pulsa sin apartar la vista.
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) dp(44),
                (int) dp(44));
        v.setLayoutParams(lp);
        v.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View w) { accion.run(); }
        });
        v.setContentDescription(simbolo == Simbolo.ANTERIOR ? "Anterior"
                : (simbolo == Simbolo.SIGUIENTE ? "Siguiente" : "Reproducir o pausar"));
        return v;
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    static final class Simbolo {
        static final int ANTERIOR = 0;
        static final int ALTERNAR = 1;
        static final int SIGUIENTE = 2;
        private Simbolo() {}
    }

    /** Fondo redondeado con borde fino, sin recursos XML. */
    static final class FondoRedondeado extends android.graphics.drawable.Drawable {
        private final int color;
        private final float radio;
        private final int borde;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        FondoRedondeado(int color, float radio, int borde) {
            this.color = color;
            this.radio = radio;
            this.borde = borde;
        }

        @Override public void draw(Canvas cv) {
            RectF caja = new RectF(0, 0, getBounds().width(), getBounds().height());
            p.setStyle(Paint.Style.FILL);
            p.setColor(color);
            cv.drawRoundRect(caja, radio, radio, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1f * cv.getDensity());
            p.setColor(borde);
            cv.drawRoundRect(caja, radio, radio, p);
        }

        @Override public void setAlpha(int a) {}
        @Override public void setColorFilter(android.graphics.ColorFilter cf) {}
        @Override public int getOpacity() { return android.graphics.PixelFormat.OPAQUE; }
    }
}
