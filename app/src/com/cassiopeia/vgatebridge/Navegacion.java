package com.cassiopeia.vgatebridge;

import android.app.Notification;
import android.content.Context;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;

import java.util.List;

/**
 * El panel de navegación.
 *
 * QUÉ SE PUEDE HACER Y QUÉ NO, sin inventar:
 *
 *  - Abrir Maps o Waze a pantalla completa: sí, es lo que hace un launcher. El mapa
 *    se ve en su aplicación, no dentro de la nuestra.
 *  - Mostrar el mapa AQUÍ DENTRO: no sin una API de pago (Google Maps SDK) o una
 *    caché de teselas propia, y las teselas de OpenStreetMap no se pueden usar desde
 *    una aplicación sin contratar un proveedor. No se hace, y se dice.
 *  - Mostrar el ESTADO de la navegación: sí, y de una forma que ya funciona con
 *    cualquier aplicación: Maps y Waze publican su navegación como notificación, y
 *    el servicio de notificaciones que ya hace falta para la música permite leerla.
 *    De ahí salen la instrucción actual y el destino, sin claves ni integraciones
 *    específicas.
 *
 * Por eso este panel enseña la maniobra en curso cuando la hay, y cuando no la hay
 * ofrece los accesos para empezar a navegar. Nunca un hueco vacío que parezca avería.
 */
final class Navegacion {

    /** Paquetes que se consideran de navegación si la configuración no dice otra cosa. */
    private static final String[] POR_DEFECTO = {
            "com.google.android.apps.maps",
            "com.waze",
            "com.google.android.apps.mapslite",
            "app.organicmaps",
            "com.mapfactor.navigator",
    };

    static final class Estado {
        /** ¿Hay una navegación en curso? */
        boolean navegando;
        /** La indicación de ahora: "Gira a la derecha en 200 m". */
        String instruccion;
        /** La calle o destino, si la aplicación lo publica. */
        String destino;
        /** De qué aplicación viene. */
        String paquete;
        /** ¿Tenemos permiso para leer notificaciones? Sin él no se puede saber nada. */
        boolean permiso;
    }

    private Navegacion() {}

    /** Estado de la navegación, leído de las notificaciones de las aplicaciones de mapas. */
    static Estado leer(Context ctx, List<String> paquetesConfigurados) {
        Estado e = new Estado();
        e.permiso = MediaSesion.permisoConcedido(ctx);
        if (!e.permiso) return e;

        StatusBarNotification[] activas = MediaListener.activas();
        if (activas == null) return e;

        for (int i = 0; i < activas.length; i++) {
            StatusBarNotification sbn = activas[i];
            if (sbn == null || sbn.getNotification() == null) continue;
            String paquete = sbn.getPackageName();
            if (!esNavegacion(paquete, paquetesConfigurados)) continue;

            Notification n = sbn.getNotification();
            Bundle extras = n.extras;
            if (extras == null) continue;

            String titulo = texto(extras, Notification.EXTRA_TITLE);
            String cuerpo = texto(extras, Notification.EXTRA_TEXT);
            String sub = texto(extras, Notification.EXTRA_SUB_TEXT);

            // Las aplicaciones de navegación usan estas notificaciones para indicar la
            // maniobra. Si además hay una categoría de navegación, es navegación segura.
            // "ongoing" no es un campo: es una marca dentro de flags (lo corrigió el
            // compilador). Una navegación en curso es una notificación que no se puede
            // descartar, además de llevar categoría de navegación.
            boolean persistente = (n.flags & Notification.FLAG_ONGOING_EVENT) != 0;
            boolean esNavegacion = Notification.CATEGORY_NAVIGATION.equals(n.category)
                    || persistente || titulo != null || cuerpo != null;
            if (!esNavegacion) continue;

            e.navegando = true;
            e.paquete = paquete;
            e.instruccion = primeraNoVacia(titulo, cuerpo);
            e.destino = sub != null ? sub : (cuerpo != null && !cuerpo.equals(e.instruccion) ? cuerpo : null);
            return e;    // la primera que aparezca es la que está navegando
        }
        return e;
    }

    /** ¿Ese paquete es una aplicación de navegación (configurada o de las de serie)? */
    static boolean esNavegacion(String paquete, List<String> configurados) {
        if (paquete == null) return false;
        if (configurados != null) {
            for (int i = 0; i < configurados.size(); i++) {
                if (paquete.equals(configurados.get(i))) return true;
            }
        }
        for (int i = 0; i < POR_DEFECTO.length; i++) {
            if (POR_DEFECTO[i].equals(paquete)) return true;
        }
        return false;
    }

    /** Abre la aplicación de navegación elegida. */
    static boolean abrir(Context ctx, String paquete) {
        return Apps.abrir(ctx, paquete);
    }

    /** La aplicación de navegación preferida, o la primera disponible de las conocidas. */
    static String preferida(Context ctx, String configurada) {
        if (configurada != null && Apps.existe(ctx, configurada)) return configurada;
        for (int i = 0; i < POR_DEFECTO.length; i++) {
            if (Apps.existe(ctx, POR_DEFECTO[i])) return POR_DEFECTO[i];
        }
        return null;
    }

    private static String primeraNoVacia(String a, String b) {
        if (a != null && a.trim().length() > 0) return a.trim();
        if (b != null && b.trim().length() > 0) return b.trim();
        return null;
    }

    private static String texto(Bundle extras, String clave) {
        CharSequence cs = extras.getCharSequence(clave);
        if (cs == null) return null;
        String v = cs.toString().trim();
        return v.length() == 0 ? null : v;
    }
}
