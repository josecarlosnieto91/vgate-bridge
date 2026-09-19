package com.cassiopeia.vgatebridge;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * El panel de navegación: la zona grande de la pantalla.
 *
 * Qué es y qué no es, para que nadie se lleve a engaño:
 *
 *  - NO incrusta el mapa. Eso exigiría una API de pago o una caché de teselas propia,
 *    y las de OpenStreetMap no se pueden usar desde una aplicación sin contratar un
 *    proveedor. No se hace, y aquí está escrito para que no se intente cada seis meses.
 *  - SÍ enseña la maniobra en curso cuando hay navegación, leída de la notificación
 *    que publican Maps o Waze. Es la vía que funciona con cualquier aplicación de
 *    mapas, sin claves ni integraciones a medida.
 *  - SÍ abre la aplicación elegida a pantalla completa, que es lo que hace un launcher
 *    de coche de verdad.
 *
 * Tres estados, y los tres se explican: navegando (la instrucción), sin navegación
 * (los botones para empezar) y sin permiso (cómo concederlo). Nunca un hueco vacío.
 */
final class NavegacionView extends LinearLayout {

    private final Paleta pal;
    private final Ajustes ajustes;
    private final TextView titulo;
    private final TextView detalle;
    private final TextView pie;
    private final LinearLayout botones;
    private final TextView aviso;
    private final View botonPermiso;

    private String ultimoEstado;

    NavegacionView(Context ctx, Paleta pal, Ajustes ajustes) {
        super(ctx);
        this.pal = pal;
        this.ajustes = ajustes;
        setOrientation(VERTICAL);
        setPadding((int) dp(14), (int) dp(10), (int) dp(14), (int) dp(10));
        setBackground(new MusicaFranja.FondoRedondeado(pal.sup, dp(10), pal.borde));

        TextView cabecera = new TextView(ctx);
        cabecera.setText("NAVEGACIÓN");
        cabecera.setTextSize(10);
        cabecera.setLetterSpacing(0.12f);
        addView(cabecera);

        titulo = new TextView(ctx);
        titulo.setTextSize(21);
        titulo.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        titulo.setMaxLines(2);
        LinearLayout.LayoutParams lp = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.topMargin = (int) dp(4);
        addView(titulo, lp);

        detalle = new TextView(ctx);
        detalle.setTextSize(13);
        detalle.setSingleLine(true);
        detalle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        addView(detalle);

        botones = new LinearLayout(ctx);
        botones.setOrientation(HORIZONTAL);
        LinearLayout.LayoutParams lpB = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) dp(46));
        lpB.topMargin = (int) dp(6);
        addView(botones, lpB);

        aviso = new TextView(ctx);
        aviso.setTextSize(11);
        addView(aviso);

        pie = new TextView(ctx);
        pie.setTextSize(10);
        addView(pie);

        botonPermiso = boton("Conceder acceso", new Runnable() {
            @Override public void run() { MediaSesion.ajustesNotificaciones(getContext()); }
        });
        addView(botonPermiso, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) dp(38)));

        this.paleta(pal);
        pintar(new Navegacion.Estado(), null);
    }

    void paleta(Paleta nueva) {
        setBackground(new MusicaFranja.FondoRedondeado(nueva.sup, dp(10), nueva.borde));
        invalidate();
    }

    /**
     * Pinta el estado de la navegación.
     *
     * Solo se toca la jerarquía de vistas cuando algo cambia de verdad: reconstruir los
     * botones cada dos segundos en un SoC flojo se nota y hace parpadear la pantalla.
     */
    void pintar(Navegacion.Estado nav, String paqueteConfigurado) {
        if (nav == null) nav = new Navegacion.Estado();
        Context ctx = getContext();

        String firma = nav.permiso + "|" + nav.navegando + "|" + nav.instruccion
                + "|" + nav.destino + "|" + paqueteConfigurado;
        if (firma.equals(ultimoEstado)) return;
        ultimoEstado = firma;

        // Cabecera del panel (el primer hijo es el rótulo).
        TextView cabecera = (TextView) getChildAt(0);
        cabecera.setTextColor(pal.sec);

        // LOS BOTONES SE PINTAN SIEMPRE. Abrir Maps o Waze no necesita ningún permiso;
        // solo leer la instrucción de la maniobra lo necesita. Tenerlos escondidos
        // detrás de un permiso que no les hace falta dejaba al conductor sin acceso
        // rápido a la navegación, que es justo lo contrario de lo que se busca.
        if (!nav.permiso) {
            titulo.setText("Navegación");
            titulo.setTextColor(pal.sec);
            detalle.setText("Para ver la maniobra en curso hace falta el acceso a notificaciones");
            montarBotones(null);
            aviso.setVisibility(VISIBLE);
            aviso.setTextColor(pal.sec);
            aviso.setText("Abrir Maps o Waze funciona igual; solo falta ver dónde vas");
            botonPermiso.setVisibility(VISIBLE);
            pie.setText("");
            Pizarra.anota("Navegación", "sin permiso (datos), botones disponibles");
            return;
        }

        botonPermiso.setVisibility(GONE);
        aviso.setVisibility(GONE);

        if (nav.navegando) {
            titulo.setText(nav.instruccion == null ? "Navegando" : nav.instruccion);
            titulo.setTextColor(pal.acento);
            detalle.setText(nav.destino == null ? "" : nav.destino);
            detalle.setTextColor(pal.sec);
            botones.setVisibility(VISIBLE);
            montarBotones(nav.paquete);
            pie.setText("Navegación activa en " + Apps.etiqueta(ctx, nav.paquete));
            pie.setTextColor(pal.apagado);
            Pizarra.anota("Navegación", nav.instruccion == null ? "navegando" : nav.instruccion);
        } else {
            titulo.setText("Sin navegación");
            titulo.setTextColor(pal.sec);
            detalle.setText("Elige una aplicación para empezar");
            detalle.setTextColor(pal.apagado);
            botones.setVisibility(VISIBLE);
            montarBotones(null);
            pie.setText("");
            Pizarra.anota("Navegación", "sin navegación");
        }
        setContentDescription("Navegación: " + titulo.getText());
    }

    /** Monta los botones de las aplicaciones de navegación que existan de verdad. */
    private void montarBotones(String paqueteActivo) {
        // El orden sale de la configuración: el primero es la aplicación preferida.
        List<String> candidatos = new ArrayList<String>();
        String preferida = ajustes == null ? null : ajustes.navegacion();
        if (preferida != null) candidatos.add(preferida);
        List<String> configuradas = ajustes == null ? null : ajustes.navegacionApps();
        if (configuradas != null) {
            for (int i = 0; i < configuradas.size(); i++) {
                if (!candidatos.contains(configuradas.get(i))) candidatos.add(configuradas.get(i));
            }
        }
        String[] conocidas = {"com.google.android.apps.maps", "com.waze"};
        for (int i = 0; i < conocidas.length; i++) {
            if (!candidatos.contains(conocidas[i])) candidatos.add(conocidas[i]);
        }

        List<String> disponibles = new ArrayList<String>();
        for (int i = 0; i < candidatos.size(); i++) {
            if (Apps.existe(getContext(), candidatos.get(i)) && !disponibles.contains(candidatos.get(i))) {
                disponibles.add(candidatos.get(i));
            }
        }
        // Si ya se está navegando, la aplicación activa va primero.
        if (paqueteActivo != null) {
            disponibles.remove(paqueteActivo);
            disponibles.add(0, paqueteActivo);
        }

        botones.removeAllViews();
        if (disponibles.isEmpty()) {
            TextView t = new TextView(getContext());
            t.setText("No hay ninguna aplicación de navegación instalada");
            t.setTextSize(11);
            t.setTextColor(pal.apagado);
            botones.addView(t);
            return;
        }
        for (int i = 0; i < disponibles.size() && i < 3; i++) {
            final String paquete = disponibles.get(i);
            final boolean activo = paquete.equals(paqueteActivo);
            View b = boton(Apps.etiqueta(getContext(), paquete), new Runnable() {
                @Override public void run() {
                    Diario.info("Navegación", "abriendo " + paquete);
                    Navegacion.abrir(getContext(), paquete);
                }
            });
            b.setSelected(activo);
            if (activo) b.setBackgroundColor(pal.marca);
            LinearLayout.LayoutParams lp = new LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            lp.rightMargin = (int) dp(6);
            botones.addView(b, lp);
        }
        botones.setVisibility(VISIBLE);
    }

    /** Un botón grande, como todo lo que se pulsa conduciendo. */
    private TextView boton(String texto, final Runnable accion) {
        TextView t = new TextView(getContext());
        t.setText(texto);
        t.setTextSize(14);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        t.setTextColor(pal.acento);
        t.setBackground(new MusicaFranja.FondoRedondeado(pal.marca, dp(8), pal.borde));
        t.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { accion.run(); }
        });
        t.setContentDescription("Abrir " + texto);
        return t;
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
