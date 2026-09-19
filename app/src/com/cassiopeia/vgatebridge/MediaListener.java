package com.cassiopeia.vgatebridge;

import android.service.notification.NotificationListenerService;

/**
 * Servicio vacío que existe SOLO para poder pedir las sesiones multimedia.
 *
 * Android no deja leer el reproductor activo (Spotify, la radio…) sin un
 * NotificationListenerService declarado y con el permiso «acceso a
 * notificaciones» concedido a mano en Ajustes. Este servicio no lee notificaciones
 * ni guarda nada: es el componente que el sistema exige como llave.
 *
 * Si el permiso no está concedido, Android lanza SecurityException al pedir las
 * sesiones y la pantalla muestra el aviso con el enlace para concederlo. Nunca se
 * queda en blanco sin explicación.
 */
public class MediaListener extends NotificationListenerService {
    // Sin lógica a propósito: la clase es la llave, no el lector.
}
