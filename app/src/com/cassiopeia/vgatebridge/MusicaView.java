package com.cassiopeia.vgatebridge;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * El reproductor integrado: carátula, título, artista, álbum y controles.
 *
 * Los controles se pulsan CONDUCIENDO, así que cada botón mide 48 puntos de alto y
 * ancho como mínimo, con el símbolo dibujado a mano y sin adornos: se acierta sin
 * apartar la vista de la carretera.
 *
 * Tres estados, y los tres se dicen:
 *  - Falta el permiso de notificaciones → se explica y se ofrece concederlo.
 *  - No hay nada sonando → se dice, no se deja un hueco mudo.
 *  - Hay reproducción → carátula, datos y controles.
 *
 * La información viene de MediaSession, así que funciona con Spotify y con cualquier
 * otro reproductor que publique su sesión. No hace falta Internet para pintarla.
 */
final class MusicaView extends LinearLayout {

    private final Paleta pal;
    private final FrameLayout marcoCaratula;
    private final TextView titulo;
    private final TextView artista;
    private final TextView album;
    private final LinearLayout controles;
    private final TextView aviso;
    private final View botonPermiso;

    private MediaSesion.Info ultima;
    private Bitmap caratulaPintada;

    MusicaView(Context ctx, Paleta pal) {
        super(ctx);
        this.pal = pal;
        setOrientation(VERTICAL);
        setPadding((int) dp(12), (int) dp(10), (int) dp(12), (int) dp(10));
        setBackground(new FondoRedondeado(pal.sup, dp(10), pal.borde));

        // ── Fila de arriba: carátula + datos ─────────────────────────────────
        LinearLayout fila = new LinearLayout(ctx);
        fila.setOrientation(HORIZONTAL);
        addView(fila, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));

        marcoCaratula = new FrameLayout(ctx) {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            protected void onDraw(Canvas cv) {
                float r = dp(8);
                RectF caja = new RectF(0, 0, getWidth(), getHeight());
                p.setColor(pal.marca);
                cv.drawRoundRect(caja, r, r, p);
                if (caratulaPintada != null) {
                    Path recorte = new Path();
                    recorte.addRoundRect(caja, r, r, Path.Direction.CW);
                    cv.save();
                    cv.clipPath(recorte);
                    cv.drawBitmap(caratulaPintada, null, caja, null);
                    cv.restore();
                } else {
                    // Sin carátula: una nota musical discreta, no un cuadro vacío.
                    p.setColor(pal.apagado);
                    p.setStyle(Paint.Style.FILL);
                    float cx = getWidth() / 2f, cy = getHeight() / 2f;
                    cv.drawCircle(cx - dp(4), cy + dp(6), dp(4), p);
                    cv.drawRect(cx - dp(1), cy - dp(8), cx + dp(1), cy + dp(6), p);
                }
            }
        };
        int lado = (int) dp(74);
        LinearLayout.LayoutParams lpArt = new LinearLayout.LayoutParams(lado, lado);
        lpArt.rightMargin = (int) dp(12);
        fila.addView(marcoCaratula, lpArt);

        LinearLayout textos = new LinearLayout(ctx);
        textos.setOrientation(VERTICAL);
        textos.setGravity(Gravity.CENTER_VERTICAL);
        fila.addView(textos, new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));

        titulo = new TextView(ctx);
        titulo.setTextSize(15);
        titulo.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        titulo.setSingleLine(true);
        titulo.setEllipsize(android.text.TextUtils.TruncateAt.END);
        textos.addView(titulo);

        artista = new TextView(ctx);
        artista.setTextSize(12.5f);
        artista.setSingleLine(true);
        artista.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams lpA = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lpA.topMargin = (int) dp(3);
        textos.addView(artista, lpA);

        album = new TextView(ctx);
        album.setTextSize(11);
        album.setSingleLine(true);
        album.setEllipsize(android.text.TextUtils.TruncateAt.END);
        textos.addView(album);

        // ── Los controles ────────────────────────────────────────────────────
        controles = new LinearLayout(ctx);
        controles.setOrientation(HORIZONTAL);
        controles.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lpC = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, (int) dp(52));
        lpC.topMargin = (int) dp(4);
        addView(controles, lpC);

        controles.addView(boton(Simbolo.ANTERIOR, new Runnable() {
            @Override public void run() { MediaSesion.control(getContext(), "anterior"); }
        }), peso());
        controles.addView(boton(Simbolo.ALTERNAR, new Runnable() {
            @Override public void run() { MediaSesion.alternar(getContext()); }
        }), peso());
        controles.addView(boton(Simbolo.SIGUIENTE, new Runnable() {
            @Override public void run() { MediaSesion.control(getContext(), "siguiente"); }
        }), peso());

        // ── El aviso (sin permiso o sin reproducción) ────────────────────────
        aviso = new TextView(ctx);
        aviso.setTextSize(12);
        aviso.setGravity(Gravity.CENTER);
        addView(aviso, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        botonPermiso = crearBotonPermiso(ctx);
        addView(botonPermiso, new LayoutParams(LayoutParams.MATCH_PARENT, (int) dp(34)));

        pinta(new MediaSesion.Info());
    }

    private LayoutParams peso() {
        return new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f);
    }

    void paleta(Paleta nueva) {
        // La paleta se sustituye en el sitio: este View se recrea al cambiar de tema
        // desde la actividad, que es más simple que recolorear pieza a pieza.
        invalidate();
    }

    /** Refresca con lo que diga el reproductor. */
    void pintar(MediaSesion.Info info) {
        if (info == null) info = new MediaSesion.Info();
        // Si nada ha cambiado, no se toca la jerarquía de vistas: repintar en balde
        // en un SoC flojo se nota.
        if (ultima != null && ultima.permiso == info.permiso && ultima.sonando == info.sonando
                && igual(ultima.titulo, info.titulo) && igual(ultima.artista, info.artista)
                && igual(ultima.album, info.album) && ultima.caratula == info.caratula) {
            return;
        }
        ultima = info;
        caratulaPintada = info.caratula;
        marcoCaratula.invalidate();
        pinta(info);
    }

    private void pinta(MediaSesion.Info info) {
        boolean hay = info.permiso && info.titulo != null;

        if (!info.permiso) {
            titulo.setText("Sin permiso de notificaciones");
            artista.setText("Hace falta para leer el reproductor");
            album.setText("");
            aviso.setVisibility(VISIBLE);
            aviso.setText("El acceso se concede desde los ajustes de Android");
            botonPermiso.setVisibility(VISIBLE);
        } else if (!hay) {
            titulo.setText("Sin reproducción");
            artista.setText("");
            album.setText("");
            aviso.setVisibility(GONE);
            botonPermiso.setVisibility(GONE);
        } else {
            titulo.setText(info.titulo);
            artista.setText(info.artista == null ? "" : info.artista);
            album.setText(info.album == null ? "" : info.album);
            aviso.setVisibility(GONE);
            botonPermiso.setVisibility(GONE);
        }

        titulo.setTextColor(info.permiso && hay ? (pal.acento) : pal.apagado);
        artista.setTextColor(pal.sec);
        album.setTextColor(pal.apagado);
        aviso.setTextColor(pal.sec);

        controles.setVisibility(info.permiso ? VISIBLE : GONE);
        // El botón central muestra pausa o reproducción según lo que esté pasando.
        View central = controles.getChildAt(1);
        if (central != null) central.invalidate();
        setContentDescription("Música: " + (info.titulo == null ? "sin reproducción" : info.titulo)
                + (info.sonando ? " (sonando)" : ""));
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        Pizarra.anota("Música", info.permiso ? (info.titulo == null ? "sin reproducción" : info.titulo)
                : "sin permiso");
        sonando(info.sonando);
    }

    private static boolean igual(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    /** Botón de control con el símbolo dibujado a mano. */
    private View boton(final int simbolo, final Runnable accion) {
        View v = new View(getContext()) {
            @Override
            protected void onDraw(Canvas cv) {
                float cx = getWidth() / 2f, cy = getHeight() / 2f;
                pintaSimbolo(cv, simbolo, cx, cy, pal);
            }
        };
        v.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View w) { accion.run(); }
        });
        // Zona táctil generosa: en el coche no se apunta con precisión.
        v.setMinimumWidth((int) dp(48));
        return v;
    }

    private static void pintaSimbolo(Canvas cv, int simbolo, float cx, float cy, Paleta pal) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(pal.acento);
        float r = 11 * cv.getDensity();
        if (simbolo == Simbolo.ANTERIOR || simbolo == Simbolo.SIGUIENTE) {
            float s = (simbolo == Simbolo.SIGUIENTE) ? 1f : -1f;
            Path t = new Path();
            t.moveTo(cx - s * r * 0.7f, cy - r * 0.75f);
            t.lineTo(cx + s * r * 0.5f, cy);
            t.lineTo(cx - s * r * 0.7f, cy + r * 0.75f);
            t.close();
            cv.drawPath(t, p);
        } else {
            // Alternar: dos barras (pausa) o un triángulo (reproducción), según el
            // estado real que se haya leído del reproductor.
            if (ultimoSonando) {
                p.setStrokeWidth(2.6f * cv.getDensity());
                cv.drawLine(cx - r * 0.35f, cy - r * 0.7f, cx - r * 0.35f, cy + r * 0.7f, p);
                cv.drawLine(cx + r * 0.35f, cy - r * 0.7f, cx + r * 0.35f, cy + r * 0.7f, p);
            } else {
                Path t = new Path();
                t.moveTo(cx - r * 0.4f, cy - r * 0.75f);
                t.lineTo(cx + r * 0.65f, cy);
                t.lineTo(cx - r * 0.4f, cy + r * 0.75f);
                t.close();
                cv.drawPath(t, p);
            }
        }
    }

    /** El botón para conceder el permiso, cuando falta. */
    private View crearBotonPermiso(Context ctx) {
        TextView v = new TextView(ctx);
        v.setText("Conceder acceso");
        v.setTextSize(12);
        v.setGravity(Gravity.CENTER);
        v.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View w) {
                MediaSesion.ajustesNotificaciones(getContext());
            }
        });
        return v;
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private static boolean ultimoSonando = false;

    static void sonando(boolean v) {
        ultimoSonando = v;
    }

    /** Símbolos de los controles. */
    static final class Simbolo {
        static final int ANTERIOR = 0;
        static final int ALTERNAR = 1;
        static final int SIGUIENTE = 2;
        private Simbolo() {}
    }

    /** Fondo redondeado con borde, sin recursos XML. */
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
