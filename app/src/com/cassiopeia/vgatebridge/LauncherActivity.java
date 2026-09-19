package com.cassiopeia.vgatebridge;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Bundle;
import android.os.Handler;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * La pantalla de inicio del coche, con vistas nativas.
 *
 * Esta Activity se declara como HOME: es lo primero que se ve al arrancar la tablet,
 * así que todo aquí está pensado para dos cosas — que se lea de un vistazo y que no
 * se caiga nunca. Cualquier excepción dentro del bucle de refresco iría en contra de
 * lo segundo: por eso todo el refresco va envuelto y un fallo se ignora en silencio
 * en vez de tumbar la pantalla de inicio (que dejaría la tablet sin interfaz).
 *
 * Composición, pensada para 1024×600 apaisado y para mirarla de reojo:
 *
 *   ┌──────────────────────────────────────────────────────────────┐
 *   │ reloj · fecha                              [avisos, si toca] │
 *   ├───────────────┬──────────────────────────┬───────────────────┤
 *   │ régimen       │       VELOCIDAD          │ combustible + km  │
 *   │ temperatura   │       (el principal)     │ música            │
 *   ├───────────────┴──────────────────────────┴───────────────────┤
 *   │ consumo · exterior                                           │
 *   ├──────────────────────────────────────────────────────────────┤
 *   │ accesos a apps                                  [apps] [⚙]  │
 *   └──────────────────────────────────────────────────────────────┘
 *
 * Un dato ausente se pinta con una raya, nunca con un cero: en un cuadro, mentir al
 * conductor es peor que reconocer que no se sabe.
 */
public class LauncherActivity extends Activity {

    // Umbrales REALES del coche (los mismos que tiene calibrados el recolector).
    private static final double RPM_ROJO = 4800;
    private static final double VEL_AVISO = 120;
    private static final double REFRIGERANTE_AVISO = 95;
    private static final double COMBUSTIBLE_BAJO = 15;

    private static final String[] ACCESOS_POR_DEFECTO = {
            "com.google.android.apps.maps",   // Maps
            "com.waze",                       // Waze
            "com.spotify.music",              // Spotify
            "com.tw.radio",                   // Radio
            "com.zjinnova.zlink",             // Android Auto (ZLink)
    };

    /** Pasado este tiempo sin datos, las medidas se dan por desconocidas. */
    private static final long CADUCA_MS = 5000;
    private static final long MS_ESTADO = 150;    // el coche cambia rápido
    private static final long MS_MUSICA = 2000;   // la música, no

    private final LiveState st = LiveState.INSTANCIA;
    private final Handler h = new Handler();
    private final List<String> testigosPintados = new ArrayList<String>();

    private Ajustes ajustes;
    private FrameLayout raiz;
    private LinearLayout panel;
    private FrameLayout zonaPanel;

    private InstrumentoView insRpm, insVel, insTemp;
    private BarraView barraComb;
    private NavegacionView navegacion;
    private MusicaFranja musica;
    private TextView reloj, fecha, datoConsumo, datoExt;
    private LinearLayout filaTestigos, filaAccesos;
    private CajonView cajon;

    private Paleta pal;
    private boolean noche;
    private boolean construido;
    private boolean estabaFresco = true;
    private long ultimoVolcado;

    /** El bucle de la pantalla. Se para al salir para no gastar batería en balde. */
    private final Runnable cicloEstado = new Runnable() {
        @Override public void run() {
            try {
                refrescarEstado();
            } catch (Throwable t) {
                // Una excepción aquí NO puede dejar sin interfaz a la tablet... pero
                // se cuenta: una excepción silenciada es un fallo que nadie diagnostica.
                Diario.error("Refresco", "fallo al refrescar el estado", t);
            }
            h.postDelayed(this, MS_ESTADO);
        }
    };

    private final Runnable cicloMusica = new Runnable() {
        @Override public void run() {
            try {
                // La música y la navegación se leen en la misma vuelta: las dos vienen
                // de lo que el sistema publica, así que se consultan juntas.
                if (musica != null) musica.pintar(MediaSesion.leer(LauncherActivity.this));
                if (navegacion != null) {
                    navegacion.pintar(Navegacion.leer(LauncherActivity.this,
                            ajustes.navegacionApps()), ajustes.navegacion());
                }
            } catch (Throwable t) {
                Diario.error("Musica", "fallo al leer el reproductor o la navegacion", t);
            }
            h.postDelayed(this, MS_MUSICA);
        }
    };

    /** Vuelca lo dibujado a un fichero: es la prueba de lo que hay en pantalla. */
    private final Runnable cicloPizarra = new Runnable() {
        @Override public void run() {
            try {
                File dir = getExternalFilesDir(null);
                Pizarra.volcar(new File(dir, "pintado.json"));
                Pizarra.anota("reloj", reloj == null ? "" : reloj.getText().toString());
                // El diario se vuelca cada 30 s: así se puede traer por SSH con el
                // coche en marcha, sin esperar a que se apague la tablet.
                long ahora = System.currentTimeMillis();
                if (ahora - ultimoVolcado > 30000) {
                    ultimoVolcado = ahora;
                    Diario.volcar(new File(dir, "diagnosticos.txt"));
                }
            } catch (Throwable t) {
                // La instrumentación no puede tumbar la pantalla que instrumenta.
            }
            h.postDelayed(this, 1000);
        }
    };

    private final Runnable cicloReloj = new Runnable() {
        @Override public void run() {
            try {
                pintarReloj();
            } catch (Throwable t) {
            }
            h.postDelayed(this, 20000);
        }
    };

    @Override
    protected void onCreate(Bundle estado) {
        super.onCreate(estado);
        // La pantalla va encendida mientras se conduce: el launcher está siempre a la vista.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        ajustes = new Ajustes(getExternalFilesDir(null));
        Diario.cabecera(this);
        // CRÍTICO para una instalación real: si el fichero de datos de prueba existe
        // en la tablet, la pantalla estaría enseñando datos inventados. En producción
        // no debe estar nunca, y si está, tiene que chillar.
        if (ajustes.hayEstadoInyectado()) {
            Diario.aviso("Pruebas", "HAY DATOS INYECTADOS (test_state.json): la pantalla NO "
                    + "esta mostrando el coche, sino datos de prueba. Borrar ese fichero.");
        } else {
            Diario.info("Pruebas", "sin datos inyectados: se leera el coche");
        }

        // El tema se decide por el alumbrado del coche; si aún no se sabe, de noche,
        // que es lo que menos deslumbra mientras llega el dato.
        noche = true;
        pal = Paleta.noche();

        construir();
        setContentView(raiz);

        // Si hay estado inyectado (emulador, sin coche), entra ya: así la pantalla
        // enseña algo real desde el primer fotograma y no un cuadro apagado.
        if (ajustes.hayEstadoInyectado()) ajustes.inyectarEstado(st);
        refrescarEstado();
        pintarReloj();
    }

    @Override
    protected void onResume() {
        super.onResume();
        h.removeCallbacks(cicloEstado);
        h.removeCallbacks(cicloMusica);
        h.removeCallbacks(cicloReloj);
        h.post(cicloEstado);
        h.post(cicloMusica);
        h.post(cicloReloj);
        h.post(cicloPizarra);
        ocultarBarras();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Al apagarse la tablet (el coche se apaga) se deja el diario escrito: es el
        // último momento en que se puede contar qué pasó en este trayecto.
        Diario.info("Ciclo", "la pantalla pasa a segundo plano (coche apagado?)");
        try {
            Diario.volcar(new File(getExternalFilesDir(null), "diagnosticos.txt"));
        } catch (Throwable ignored) {
        }
        // Sin esto, la pantalla seguiría consultando el coche y el reproductor con la
        // tablet apagada o en segundo plano.
        h.removeCallbacks(cicloEstado);
        h.removeCallbacks(cicloMusica);
        h.removeCallbacks(cicloReloj);
        h.removeCallbacks(cicloPizarra);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        h.removeCallbacksAndMessages(null);
    }

    // ── Construcción de la pantalla ──────────────────────────────────────────

    private void construir() {
        raiz = new FrameLayout(this);

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(pal.fondo);
        int m = dp(16);
        panel.setPadding(m, dp(10), m, dp(10));

        panel.addView(filaSuperior(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(38)));

        LinearLayout principal = new LinearLayout(this);
        principal.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lpPrincipal = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        lpPrincipal.topMargin = dp(4);
        panel.addView(principal, lpPrincipal);

        // ── Columna izquierda: la conducción. La velocidad manda. ────────────
        LinearLayout izq = new LinearLayout(this);
        izq.setOrientation(LinearLayout.VERTICAL);
        principal.addView(izq, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1.15f));

        insVel = new InstrumentoView(this, pal, "Velocidad", "km/h",
                0, 180, Double.valueOf(VEL_AVISO), true, true);
        LinearLayout.LayoutParams lpVel = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        lpVel.bottomMargin = dp(4);
        izq.addView(insVel, lpVel);

        // Régimen y refrigerante, pequeños y en la misma fila: son de reojo, no
        // protagonistas. Antes ocupaban una columna entera para ellos solos.
        LinearLayout filaMenuda = new LinearLayout(this);
        filaMenuda.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lpMenuda = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(104));
        lpMenuda.bottomMargin = dp(4);
        izq.addView(filaMenuda, lpMenuda);

        insRpm = new InstrumentoView(this, pal, "Régimen", "rpm ×1000",
                0, 6, Double.valueOf(RPM_ROJO / 1000.0), true, false);
        LinearLayout.LayoutParams lpRpm = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        lpRpm.rightMargin = dp(6);
        filaMenuda.addView(insRpm, lpRpm);

        insTemp = new InstrumentoView(this, pal, "Refrigerante", "°C",
                40, 120, Double.valueOf(REFRIGERANTE_AVISO), true, false);
        filaMenuda.addView(insTemp, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        // El combustible, en barra: baja en días, no merece aguja.
        barraComb = new BarraView(this, pal, "Combustible", "km", Double.valueOf(COMBUSTIBLE_BAJO));
        izq.addView(barraComb, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));

        // ── Columna derecha: LA NAVEGACIÓN manda; la música, en una franja ───
        boolean hayNavegacion = ajustes.visible("navegacion");
        boolean hayMusica = ajustes.visible("musica");

        if (hayNavegacion || hayMusica) {
            LinearLayout der = new LinearLayout(this);
            der.setOrientation(LinearLayout.VERTICAL);
            principal.addView(der, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, 1f));

            if (hayNavegacion) {
                navegacion = new NavegacionView(this, pal, ajustes);
                LinearLayout.LayoutParams lpNav = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
                lpNav.bottomMargin = dp(6);
                der.addView(navegacion, lpNav);
            }

            if (hayMusica) {
                musica = new MusicaFranja(this, pal);
                der.addView(musica, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
            }
        }

        // ── Tira de datos ────────────────────────────────────────────────────
        LinearLayout tira = new LinearLayout(this);
        tira.setOrientation(LinearLayout.HORIZONTAL);
        tira.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lpTira = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(34));
        lpTira.topMargin = dp(6);
        panel.addView(tira, lpTira);

        datoConsumo = dato(tira, "Consumo");
        datoExt = dato(tira, "Exterior");
        tira.addView(new View(this), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        // ── Accesos ──────────────────────────────────────────────────────────
        panel.addView(filaAccesos(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(72)));

        zonaPanel = new FrameLayout(this);
        zonaPanel.addView(panel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        raiz.addView(zonaPanel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ── El cajón, encima y oculto hasta que se pida ──────────────────────
        cajon = new CajonView(this, pal, ajustes);
        cajon.alCerrar(new Runnable() {
            @Override public void run() { cerrarCajon(); }
        });
        cajon.setVisibility(View.GONE);
        raiz.addView(cajon, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        construido = true;
    }

    /** Reloj, fecha y la zona donde aparecen los avisos. */
    private View filaSuperior() {
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.HORIZONTAL);
        f.setGravity(Gravity.CENTER_VERTICAL);

        reloj = new TextView(this);
        reloj.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        reloj.setTypeface(reloj.getTypeface(), android.graphics.Typeface.BOLD);
        f.addView(reloj);

        fecha = new TextView(this);
        fecha.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams lpF = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpF.leftMargin = dp(10);
        f.addView(fecha, lpF);

        f.addView(new View(this), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        filaTestigos = new LinearLayout(this);
        filaTestigos.setOrientation(LinearLayout.HORIZONTAL);
        filaTestigos.setGravity(Gravity.CENTER_VERTICAL);
        f.addView(filaTestigos);
        return f;
    }

    /** Un dato de la tira inferior con su etiqueta. */
    private TextView dato(LinearLayout padre, String etiqueta) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(18);
        padre.addView(c, lp);

        TextView et = new TextView(this);
        et.setText(etiqueta.toUpperCase());
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        et.setTextColor(pal.sec);
        c.addView(et);

        TextView v = new TextView(this);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        v.setTypeface(v.getTypeface(), android.graphics.Typeface.BOLD);
        v.setTextColor(pal.acento);
        LinearLayout.LayoutParams lpV = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpV.leftMargin = dp(6);
        c.addView(v, lpV);
        return v;
    }

    /** Los accesos directos y los dos botones de la esquina. */
    private View filaAccesos() {
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.HORIZONTAL);
        f.setGravity(Gravity.CENTER_VERTICAL);

        filaAccesos = new LinearLayout(this);
        filaAccesos.setOrientation(LinearLayout.HORIZONTAL);
        f.addView(filaAccesos, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        f.addView(botonRedondo(SimboloIcono.REJILLA, new Runnable() {
            @Override public void run() { abrirCajon(); }
        }));
        View engranaje = botonRedondo(SimboloIcono.AJUSTES, new Runnable() {
            @Override public void run() { ajustesInicio(); }
        });
        // Pulsación larga: la pantalla de diagnóstico del coche. Va aquí porque es
        // donde uno busca cuando algo no cuadra, y así el dashboard no gana un botón
        // más para algo que no se usa conduciendo.
        engranaje.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                Diario.info("Diagnostico", "abriendo el diagnostico desde el engranaje");
                try {
                    startActivity(new Intent(LauncherActivity.this, DiagnosticoActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Throwable t) {
                    Diario.error("Diagnostico", "no se pudo abrir el diagnostico", t);
                }
                return true;
            }
        });
        f.addView(engranaje);
        // Los accesos se montan AQUÍ. Estaban escritos y no se llamaban desde ningún
        // sitio: los cinco atajos configurados no llegaron a pintarse nunca y la
        // captura no lo delataba, porque la vista se centra en los instrumentos.
        montarAccesos();
        return f;
    }

    private void montarAccesos() {
        filaAccesos.removeAllViews();
        List<String> paquetes = ajustes.accesos();
        if (paquetes == null) {
            paquetes = new ArrayList<String>();
            for (int i = 0; i < ACCESOS_POR_DEFECTO.length; i++) paquetes.add(ACCESOS_POR_DEFECTO[i]);
        }
        int pintados = 0;
        StringBuilder faltan = new StringBuilder();
        for (int i = 0; i < paquetes.size(); i++) {
            final String paquete = paquetes.get(i);
            if (!Apps.existe(this, paquete)) {
                // Un acceso configurado que no está instalado es la causa número uno
                // de "no me sale la app en el launcher". Se dice, con su paquete.
                if (faltan.length() > 0) faltan.append(", ");
                faltan.append(paquete);
                continue;
            }
            filaAccesos.addView(acceso(paquete));
            pintados++;
        }
        Diario.info("Accesos", "pintados " + pintados + " de " + paquetes.size());
        if (faltan.length() > 0) {
            Diario.aviso("Accesos", "configurados pero NO instalados: " + faltan);
        }
    }

    /** Un acceso: icono real de la aplicación y su nombre. */
    private View acceso(final String paquete) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setPadding(dp(10), dp(6), dp(14), dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(56));
        lp.rightMargin = dp(8);
        c.setLayoutParams(lp);

        ImageView icono = new ImageView(this);
        int lado = dp(28);
        icono.setLayoutParams(new LinearLayout.LayoutParams(lado, lado));
        try {
            icono.setImageDrawable(getPackageManager().getApplicationIcon(paquete));
        } catch (Throwable t) {
            // Sin icono se queda el hueco, pero el acceso sigue funcionando.
        }
        c.addView(icono);

        TextView t = new TextView(this);
        t.setText(Apps.etiqueta(this, paquete));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setTextColor(pal.sec);
        t.setSingleLine(true);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp2.leftMargin = dp(8);
        c.addView(t, lp2);

        c.setContentDescription("Abrir " + Apps.etiqueta(this, paquete));
        c.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { Apps.abrir(LauncherActivity.this, paquete); }
        });
        return c;
    }

    /** Botón redondo dibujado a mano: rejilla de aplicaciones y ajustes. */
    private View botonRedondo(final int simbolo, final Runnable accion) {
        View v = new View(this) {
            @Override
            protected void onDraw(Canvas cv) {
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setColor(pal.sup);
                cv.drawCircle(getWidth() / 2f, getHeight() / 2f, getWidth() / 2f - dp(1), p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(dp(1));
                p.setColor(pal.marca);
                cv.drawCircle(getWidth() / 2f, getHeight() / 2f, getWidth() / 2f - dp(1), p);

                float cx = getWidth() / 2f, cy = getHeight() / 2f;
                p.setColor(pal.acento);
                p.setStyle(Paint.Style.FILL);
                if (simbolo == SimboloIcono.REJILLA) {
                    float s = dp(4), hueco = dp(2.5f);
                    for (int i = 0; i < 2; i++) {
                        for (int j = 0; j < 2; j++) {
                            cv.drawRoundRect(cx - s - hueco / 2 + i * (s + hueco),
                                    cy - s - hueco / 2 + j * (s + hueco),
                                    cx - hueco / 2 + i * (s + hueco),
                                    cy - hueco / 2 + j * (s + hueco), dp(1.4f), dp(1.4f), p);
                        }
                    }
                } else {
                    p.setStrokeWidth(dp(1.8f));
                    p.setStyle(Paint.Style.STROKE);
                    cv.drawCircle(cx, cy, dp(6), p);
                    p.setStyle(Paint.Style.FILL);
                    cv.drawCircle(cx, cy, dp(2), p);
                    for (int i = 0; i < 8; i++) {
                        double a = Math.toRadians(i * 45);
                        cv.drawCircle((float) (cx + Math.cos(a) * dp(8.5f)),
                                (float) (cy + Math.sin(a) * dp(8.5f)), dp(1.1f), p);
                    }
                }
            }
        };
        int lado = dp(52);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(lado, lado);
        lp.leftMargin = dp(6);
        v.setLayoutParams(lp);
        v.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View w) { accion.run(); }
        });
        v.setContentDescription(simbolo == SimboloIcono.REJILLA ? "Aplicaciones" : "Ajustes");
        return v;
    }

    /** Símbolos de los botones redondos. */
    private static final class SimboloIcono {
        static final int REJILLA = 0;
        static final int AJUSTES = 1;
        private SimboloIcono() {}
    }

    // ── Refresco ─────────────────────────────────────────────────────────────

    private void refrescarEstado() {
        if (!construido) return;
        // Estado inyectado: se relee cada vuelta para poder cambiar el fichero con la
        // pantalla abierta y ver el efecto sin reinstalar nada.
        if (ajustes.hayEstadoInyectado()) ajustes.inyectarEstado(st);

        // ── Un dato viejo NO es un dato ──────────────────────────────────────
        // Si el CAN o el OBD dejan de responder, el ultimo valor se queda escrito en
        // LiveState para siempre. Enseñarlo seria mentir: un velocimetro marcando 64
        // con el enlace caido es peor que uno que reconoce que no sabe. Pasado el
        // margen, las MEDIDAS pasan a desconocidas.
        //
        // Los estados discretos (puertas, luces) no caducan: un aviso de puerta
        // abierta que desaparece solo seria un aviso peor que inutil.
        boolean fresco = System.currentTimeMillis() - st.updatedAt <= CADUCA_MS;
        // El aviso más útil de todos en el coche: el dato dejó de llegar. Se registra
        // solo al cambiar de estado, para que el diario se pueda leer de un vistazo.
        if (fresco != estabaFresco) {
            if (fresco) {
                Diario.info("Datos", "el coche vuelve a dar datos");
            } else {
                Diario.aviso("Datos", "sin datos del coche desde hace mas de "
                        + (CADUCA_MS / 1000) + " s: los instrumentos pasan a desconocido");
            }
            estabaFresco = fresco;
        }

        insVel.valor(fresco ? st.speedKmh : null);
        insTemp.valor(fresco ? st.coolantC : null);
        // El régimen se muestra en miles, que es como se lee un cuadro.
        insRpm.valor(fresco && st.rpm != null ? Double.valueOf(st.rpm.doubleValue() / 1000.0) : null);

        barraComb.nivel(fresco ? st.fuelLevelPct : null);
        barraComb.secundario(fresco ? st.rangeKm : null);

        consumo(fresco ? st.consumptionL100 : null);
        exterior(fresco ? st.outsideTempC : null);
        testigos(fresco);
        tema();
    }

    private void consumo(Double l100) {
        String v;
        if (l100 == null) {
            v = "—";
        } else {
            v = String.format(Locale.US, "%.1f", l100.doubleValue());
        }
        datoConsumo.setText(v);
        Pizarra.anota("Consumo", v);
    }

    private void exterior(Integer c) {
        String v = c == null ? "—" : (c.intValue() + "°");
        datoExt.setText(v);
        Pizarra.anota("Exterior", v);
    }

    /**
     * Los avisos, solo cuando ocurren.
     *
     * Un testigo encendido permanentemente no avisa de nada: se aprende a ignorarlo y
     * el día que importa ya no se ve. La fila se reconstruye solo cuando cambia el
     * conjunto de avisos activos.
     */
    private void testigos(boolean fresco) {
        List<String> activos = new ArrayList<String>();
        // Puertas y luces son estados, no medidas: no caducan.
        if (Boolean.TRUE.equals(st.doorOpen)) activos.add("Puerta|" + TestigoView.PUERTA);
        if (Boolean.TRUE.equals(st.lightsOn)) activos.add("Luces|" + TestigoView.LUCES);
        // Los avisos por umbral sí dependen de medidas: con el dato viejo, se callan.
        if (fresco && st.coolantC != null && st.coolantC.doubleValue() >= REFRIGERANTE_AVISO) {
            activos.add("Temperatura|" + TestigoView.AVISO);
        }
        if (fresco && st.fuelLevelPct != null && st.fuelLevelPct.doubleValue() <= COMBUSTIBLE_BAJO) {
            activos.add("Combustible|" + TestigoView.AVISO);
        }
        if (fresco && st.speedKmh != null && st.speedKmh.doubleValue() >= VEL_AVISO) {
            activos.add("Velocidad|" + TestigoView.AVISO);
        }
        if (activos.equals(testigosPintados)) return;

        StringBuilder nombres = new StringBuilder();
        for (int i = 0; i < activos.size(); i++) {
            if (i > 0) nombres.append(", ");
            nombres.append(activos.get(i).split("\\|")[0]);
        }
        Pizarra.anota("avisos", nombres.length() == 0 ? "ninguno" : nombres.toString());

        testigosPintados.clear();
        testigosPintados.addAll(activos);
        filaTestigos.removeAllViews();
        for (int i = 0; i < activos.size(); i++) {
            String[] partes = activos.get(i).split("\\|");
            TestigoView t = new TestigoView(this, pal, Integer.parseInt(partes[1]), partes[0]);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(24));
            lp.leftMargin = dp(8);
            filaTestigos.addView(t, lp);
        }
    }

    /**
     * Día o noche: lo decide el ALUMBRADO del coche, no la hora.
     *
     * Así, con niebla a mediodía el cuadro se oscurece igual que lo haría el de serie.
     * El cambio solo se aplica cuando cambia de verdad: recolorear en cada vuelta
     * haría parpadear la pantalla y gastaría CPU para nada.
     */
    private void tema() {
        String fijado = ajustes.tema();
        boolean quiereNoche;
        if ("dia".equals(fijado)) {
            quiereNoche = false;
        } else if ("noche".equals(fijado)) {
            quiereNoche = true;
        } else {
            // ALUMBRADO ENCENDIDO = NOCHE, igual que en la version anterior: si el
            // coche lleva las luces puestas es porque esta oscuro. Invertir esto hacia
            // que de noche el cuadro saliera en claro, que es lo que deslumbra.
            quiereNoche = Boolean.TRUE.equals(st.lightsOn);
        }
        // Se anota siempre, aunque no haya que cambiar nada: una comprobacion que
        // solo ve los cambios no puede distinguir "correcto" de "no se ejecuto".
        Pizarra.anota("tema", quiereNoche ? "noche" : "dia");
        if (quiereNoche == noche && construido) return;
        Diario.info("Tema", "cambio a " + (quiereNoche ? "noche" : "dia")
                + " (alumbrado " + (Boolean.TRUE.equals(st.lightsOn) ? "encendido" : "apagado")
                + ", tema configurado: " + fijado + ")");
        noche = quiereNoche;
        pal = Paleta.de(noche);

        panel.setBackgroundColor(pal.fondo);
        reloj.setTextColor(pal.acento);
        fecha.setTextColor(pal.sec);
        datoConsumo.setTextColor(pal.acento);
        datoExt.setTextColor(pal.acento);
        insVel.paleta(pal);
        insRpm.paleta(pal);
        insTemp.paleta(pal);
        barraComb.paleta(pal);
        if (musica != null) musica.paleta(pal);
        if (navegacion != null) navegacion.paleta(pal);
        if (cajon != null) {
            cajon.removeAllViews();
            raiz.removeView(cajon);
            cajon = new CajonView(this, pal, ajustes);
            cajon.alCerrar(new Runnable() {
                @Override public void run() { cerrarCajon(); }
            });
            cajon.setVisibility(View.GONE);
            raiz.addView(cajon, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    private void pintarReloj() {
        Date ahora = new Date();
        reloj.setText(new SimpleDateFormat("HH:mm", new Locale("es", "ES")).format(ahora));
        fecha.setText(new SimpleDateFormat("EEEE d 'de' MMMM", new Locale("es", "ES")).format(ahora));
    }

    // ── Cajón, ajustes y ciclo de vida de la ventana ─────────────────────────

    private void abrirCajon() {
        if (cajon == null) return;
        Diario.info("Cajon", "abriendo la lista de aplicaciones");
        cajon.cargar();
        cajon.setVisibility(View.VISIBLE);
        cajon.bringToFront();
    }

    private void cerrarCajon() {
        if (cajon != null) cajon.setVisibility(View.GONE);
    }

    /** Salida de emergencia: los ajustes de inicio de Android, para poder volver al
     *  launcher de la ROM si algún día esta pantalla se queda sin configurar. */
    private void ajustesInicio() {
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_HOME_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            Intent i = new Intent(android.provider.Settings.ACTION_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        }
    }

    @Override
    public void onBackPressed() {
        // Atrás no cierra la pantalla de inicio: si lo hiciera, la tablet se quedaría
        // sin interfaz y habría que reiniciarla. Si el cajón está abierto, lo cierra.
        if (cajon != null && cajon.getVisibility() == View.VISIBLE) {
            cerrarCajon();
            return;
        }
        // Nada más: se ignora.
    }

    @Override
    public void onWindowFocusChanged(boolean tieneFoco) {
        super.onWindowFocusChanged(tieneFoco);
        if (tieneFoco) ocultarBarras();
    }

    /** Pantalla completa, sin la barra de estado. */
    private void ocultarBarras() {
        try {
            View decor = getWindow().getDecorView();
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        } catch (Throwable ignored) {
        }
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
