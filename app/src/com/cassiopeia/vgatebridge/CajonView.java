package com.cassiopeia.vgatebridge;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * El cajón de aplicaciones: la rejilla con todo lo instalado.
 *
 * Es lo que convierte esto en un launcher de verdad y no en un panel con atajos. Los
 * iconos son los reales, traídos del gestor de paquetes, y las apps se abren de forma
 * nativa: aquí no se incrusta nada de terceros, que es justamente lo que no se puede
 * hacer ni se debe intentar.
 *
 * Arriba van los favoritos (lo que de verdad se usa conduciendo) y debajo la rejilla
 * completa. Un toque abre y cierra el cajón: no hay que buscar un botón de volver.
 */
final class CajonView extends LinearLayout {

    private final Paleta pal;
    private final List<Apps.App> todas = new ArrayList<Apps.App>();
    private final List<Apps.App> favoritas = new ArrayList<Apps.App>();
    private final Ajustes ajustes;
    private final GridView rejilla;
    private final LinearLayout filaFavoritos;
    private final ListaAdapter adaptador;
    private Runnable alCerrar;

    CajonView(Context ctx, Paleta pal, Ajustes ajustes) {
        super(ctx);
        this.pal = pal;
        this.ajustes = ajustes;
        setOrientation(VERTICAL);
        setBackgroundColor(pal.fondo);
        setPadding((int) dp(18), (int) dp(12), (int) dp(18), (int) dp(12));

        // ── Cabecera: título y cerrar ────────────────────────────────────────
        LinearLayout cabecera = new LinearLayout(ctx);
        cabecera.setOrientation(HORIZONTAL);
        cabecera.setGravity(Gravity.CENTER_VERTICAL);
        addView(cabecera, new LayoutParams(LayoutParams.MATCH_PARENT, (int) dp(34)));

        TextView t = new TextView(ctx);
        t.setText("Aplicaciones");
        t.setTextSize(17);
        t.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        t.setTextColor(pal.acento);
        cabecera.addView(t, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        TextView cerrar = new TextView(ctx);
        cerrar.setText("Cerrar  ✕");
        cerrar.setTextSize(14);
        cerrar.setTextColor(pal.sec);
        cerrar.setPadding((int) dp(14), (int) dp(6), (int) dp(14), (int) dp(6));
        cerrar.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                if (alCerrar != null) alCerrar.run();
            }
        });
        cabecera.addView(cerrar);

        // ── Favoritos ────────────────────────────────────────────────────────
        filaFavoritos = new LinearLayout(ctx);
        filaFavoritos.setOrientation(HORIZONTAL);
        LinearLayout.LayoutParams lpF = new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lpF.topMargin = (int) dp(6);
        lpF.bottomMargin = (int) dp(6);
        addView(filaFavoritos, lpF);

        // ── La rejilla completa ──────────────────────────────────────────────
        rejilla = new GridView(ctx);
        rejilla.setNumColumns(6);
        rejilla.setVerticalSpacing((int) dp(10));
        rejilla.setHorizontalSpacing((int) dp(6));
        rejilla.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        rejilla.setSelector(new android.graphics.drawable.ColorDrawable(0x00000000));
        adaptador = new ListaAdapter();
        rejilla.setAdapter(adaptador);
        addView(rejilla, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));
    }

    void alCerrar(Runnable r) {
        this.alCerrar = r;
    }

    /** Recarga la lista de aplicaciones instaladas. */
    void cargar() {
        todas.clear();
        todas.addAll(Apps.instaladas(getContext()));
        favoritas.clear();
        List<String> pedidas = ajustes == null ? null : ajustes.favoritos();
        if (pedidas != null) {
            for (int i = 0; i < pedidas.size(); i++) {
                String paquete = pedidas.get(i);
                for (int j = 0; j < todas.size(); j++) {
                    if (todas.get(j).paquete.equals(paquete)) {
                        favoritas.add(todas.get(j));
                        break;
                    }
                }
            }
        }
        adaptador.notifyDataSetChanged();
        montarFavoritos();
    }

    private void montarFavoritos() {
        filaFavoritos.removeAllViews();
        if (favoritas.isEmpty()) {
            TextView t = new TextView(getContext());
            t.setText("Sin favoritos: añádelos en launcher.json");
            t.setTextSize(11);
            t.setTextColor(pal.apagado);
            filaFavoritos.addView(t);
            return;
        }
        for (int i = 0; i < favoritas.size(); i++) {
            final Apps.App a = favoritas.get(i);
            LinearLayout celda = celda(a, true);
            filaFavoritos.addView(celda);
        }
    }

    /** Una celda: icono real arriba y nombre debajo. */
    private LinearLayout celda(final Apps.App a, boolean pequena) {
        Context ctx = getContext();
        LinearLayout c = new LinearLayout(ctx);
        c.setOrientation(VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.setPadding((int) dp(8), (int) dp(4), (int) dp(8), (int) dp(4));
        int ancho = pequena ? (int) dp(64) : ViewGroup.LayoutParams.MATCH_PARENT;
        c.setLayoutParams(new LinearLayout.LayoutParams(ancho, LayoutParams.WRAP_CONTENT));

        ImageView icono = new ImageView(ctx);
        int lado = (int) dp(pequena ? 26 : 34);
        icono.setLayoutParams(new LinearLayout.LayoutParams(lado, lado));
        if (a.icono != null) icono.setImageDrawable(a.icono);
        c.addView(icono);

        TextView nombre = new TextView(ctx);
        nombre.setText(a.etiqueta);
        nombre.setTextSize(pequena ? 10 : 11);
        nombre.setGravity(Gravity.CENTER);
        nombre.setSingleLine(true);
        nombre.setEllipsize(android.text.TextUtils.TruncateAt.END);
        nombre.setTextColor(pal.sec);
        LinearLayout.LayoutParams lpN = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lpN.topMargin = (int) dp(3);
        c.addView(nombre, lpN);

        c.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                Apps.abrir(getContext(), a.paquete);
                if (alCerrar != null) alCerrar.run();
            }
        });
        c.setContentDescription(a.etiqueta);
        return c;
    }

    /** Adaptador sin XML: construye y REUTILIZA las celdas que da el GridView. */
    private final class ListaAdapter extends BaseAdapter {
        @Override public int getCount() { return todas.size(); }
        @Override public Object getItem(int i) { return todas.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int i, View reciclada, ViewGroup padre) {
            Apps.App a = todas.get(i);
            if (reciclada instanceof LinearLayout) {
                LinearLayout c = (LinearLayout) reciclada;
                ((ImageView) c.getChildAt(0)).setImageDrawable(a.icono);
                ((TextView) c.getChildAt(1)).setText(a.etiqueta);
                c.setContentDescription(a.etiqueta);
                final LinearLayout cc = c;
                c.setOnClickListener(new OnClickListener() {
                    @Override public void onClick(View v) {
                        Apps.abrir(getContext(), ((Apps.App) cc.getTag()).paquete);
                        if (alCerrar != null) alCerrar.run();
                    }
                });
                c.setTag(a);
                return c;
            }
            LinearLayout c = celda(a, false);
            c.setTag(a);
            return c;
        }
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
