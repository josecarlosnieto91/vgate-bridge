package com.cassiopeia.vgatebridge;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Las aplicaciones instaladas que se pueden abrir.
 *
 * Consulta al gestor de paquetes las que tienen pantalla de inicio, se queda con una
 * por paquete (una app con dos actividades lanzables no debe salir dos veces) y las
 * ordena por nombre con un cotejador español, para que "Álbum" caiga donde debe y no
 * detrás de la Z. Se excluye a sí misma: el launcher no se abre a sí mismo.
 *
 * Los iconos son los REALES de cada aplicación (loadIcon), no una inicial como en la
 * versión HTML: en nativo no cuesta nada y es lo que se espera de un launcher.
 */
final class Apps {

    static final class App {
        final String etiqueta;
        final String paquete;
        final Drawable icono;

        App(String etiqueta, String paquete, Drawable icono) {
            this.etiqueta = etiqueta;
            this.paquete = paquete;
            this.icono = icono;
        }
    }

    private Apps() {}

    /** Apps instaladas con pantalla de inicio, sin duplicados y ordenadas por nombre. */
    static List<App> instaladas(Context ctx) {
        List<App> salida = new ArrayList<App>();
        try {
            PackageManager pm = ctx.getPackageManager();
            Intent principal = new Intent(Intent.ACTION_MAIN, null);
            principal.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> resueltos = pm.queryIntentActivities(principal, 0);
            if (resueltos == null) return salida;

            Map<String, App> porPaquete = new LinkedHashMap<String, App>();
            for (int i = 0; i < resueltos.size(); i++) {
                ResolveInfo ri = resueltos.get(i);
                if (ri == null || ri.activityInfo == null) continue;
                ActivityInfo ai = ri.activityInfo;
                String paquete = ai.packageName;
                if (paquete == null || paquete.equals(ctx.getPackageName())) continue;
                if (porPaquete.containsKey(paquete)) continue;

                CharSequence etiquetaCs = ri.loadLabel(pm);
                String etiqueta = etiquetaCs == null ? paquete : etiquetaCs.toString().trim();
                if (etiqueta.length() == 0) etiqueta = paquete;

                Drawable icono = null;
                try {
                    icono = ri.loadIcon(pm);
                } catch (Throwable t) {
                    // Un icono que no carga no puede dejar la lista sin esa app: saldrá
                    // con el hueco. Pero se cuenta, porque es un síntoma de algo peor.
                    Diario.aviso("Aplicaciones", "sin icono para " + paquete + ": " + t);
                }
                porPaquete.put(paquete, new App(etiqueta, paquete, icono));
            }

            salida.addAll(porPaquete.values());
            Diario.info("Aplicaciones", "instaladas y lanzables: " + salida.size());
            final Collator cotejador = Collator.getInstance(new Locale("es", "ES"));
            Collections.sort(salida, new Comparator<App>() {
                @Override
                public int compare(App a, App b) {
                    return cotejador.compare(a.etiqueta, b.etiqueta);
                }
            });
        } catch (Throwable t) {
            // Nunca propagar: el cajón vacío es un problema, una excepción en la
            // pantalla de inicio es otro mucho peor.
        }
        return salida;
    }

    /** ¿Está instalada esa aplicación? Se comprueba antes de pintar un acceso. */
    static boolean existe(Context ctx, String paquete) {
        if (paquete == null || paquete.length() == 0) return false;
        try {
            ctx.getPackageManager().getLaunchIntentForPackage(paquete);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Abre una aplicación como aplicación normal. Devuelve si se pudo. */
    static boolean abrir(Context ctx, String paquete) {
        try {
            Intent i = ctx.getPackageManager().getLaunchIntentForPackage(paquete);
            if (i == null) {
                Diario.aviso("Aplicaciones", "no se puede abrir " + paquete
                        + ": no tiene pantalla de inicio (¿desinstalada?)");
                return false;
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            Diario.info("Aplicaciones", "abierta " + paquete);
            return true;
        } catch (Throwable t) {
            Diario.error("Aplicaciones", "fallo al abrir " + paquete, t);
            return false;
        }
    }

    /** Nombre visible de una aplicación, o el propio paquete si no se puede leer. */
    static String etiqueta(Context ctx, String paquete) {
        try {
            PackageManager pm = ctx.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(paquete, 0)).toString();
        } catch (Throwable t) {
            return paquete;
        }
    }

    /** Componente lanzable, para el atajo "abrir cualquier cosa que se pueda abrir". */
    static ComponentName componente(Context ctx, String paquete) {
        try {
            Intent i = ctx.getPackageManager().getLaunchIntentForPackage(paquete);
            return i == null ? null : i.getComponent();
        } catch (Throwable t) {
            return null;
        }
    }
}
