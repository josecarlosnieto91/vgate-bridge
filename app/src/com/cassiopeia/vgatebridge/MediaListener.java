package com.cassiopeia.vgatebridge;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * La llave del sistema para leer lo que publican otras aplicaciones.
 *
 * Android no deja leer el reproductor activo (Spotify, la radio) ni el estado de la
 * navegación (Maps, Waze) sin un NotificationListenerService declarado y con el
 * permiso «acceso a notificaciones» concedido a mano en Ajustes. Esta clase es ese
 * componente.
 *
 * QUÉ HACE CON LO QUE VE: nada más que tenerlo a mano en memoria mientras el sistema
 * lo publica, para que la pantalla pueda leerlo. No se guarda en disco, no se envía a
 * ningún sitio y no se abre ninguna conexión. Si el permiso no está concedido, Android
 * ni siquiera llama aquí, y la pantalla lo dice con un acceso para concederlo.
 *
 * Antes esta clase estaba vacía a propósito; ahora expone la lista porque el panel de
 * navegación la necesita. Es la misma llave, con el lector dentro.
 */
public class MediaListener extends NotificationListenerService {

    /** La última instancia viva: el sistema crea y destruye este servicio. */
    private static volatile MediaListener instancia;
    // getActiveNotifications() devuelve un ARRAY, no una lista: lo comprobó el
    // compilador, no la memoria.
    private static volatile StatusBarNotification[] ultimas;

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        instancia = this;
        Diario.info("Permisos", "acceso a notificaciones concedido: ya se puede leer el "
                + "reproductor y el estado de la navegacion");
        obtener();
    }

    @Override
    public void onListenerDisconnected() {
        instancia = null;
        Diario.aviso("Permisos", "acceso a notificaciones retirado: la musica y la "
                + "navegacion dejaran de mostrar su estado");
        super.onListenerDisconnected();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        obtener();
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        obtener();
    }

    /** Pide al sistema la lista de notificaciones activas y la guarda en memoria. */
    private void obtener() {
        try {
            ultimas = getActiveNotifications();
        } catch (Throwable t) {
            ultimas = null;
        }
    }

    /** Las notificaciones activas que el sistema nos deja ver, o null si no hay permiso. */
    static StatusBarNotification[] activas() {
        MediaListener l = instancia;
        if (l == null) return ultimas;
        try {
            StatusBarNotification[] ahora = l.getActiveNotifications();
            if (ahora != null) ultimas = ahora;
        } catch (Throwable t) {
            // Servicio caído a medias: se devuelve lo último que hubiera.
        }
        return ultimas;
    }
}
