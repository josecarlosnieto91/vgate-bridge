package com.cassiopeia.vgatebridge;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.provider.Settings;

import java.util.List;

/**
 * El reproductor que esté sonando, leído de MediaSession.
 *
 * Es la vía correcta: funciona con Spotify y con cualquier otro reproductor que
 * publique su sesión, sin depender de Internet, sin claves de API y sin saber nada de
 * cada aplicación. Lo que suene fuera de MediaSession no se puede leer, y se dice.
 *
 * Necesita el permiso de acceso a notificaciones: sin él, getActiveSessions lanza
 * SecurityException y aquí se traduce a "permiso: no", que la pantalla muestra con un
 * acceso para concederlo. Un hueco mudo sería peor: parecería una avería nuestra.
 */
final class MediaSesion {

    static final class Info {
        boolean permiso;          // ¿tenemos acceso a las notificaciones?
        boolean sonando;          // ¿está reproduciendo ahora mismo?
        String titulo;
        String artista;
        String album;
        Bitmap caratula;          // puede ser null: no todos los reproductores la dan
    }

    private MediaSesion() {}

    /** Estado del reproductor activo. Nunca lanza: siempre devuelve algo pintable. */
    static Info leer(Context ctx) {
        Info info = new Info();

        // Inyección para pruebas: en el emulador no hay Spotify, y sin esto el
        // reproductor no se podría ver nunca hasta subirlo al coche.
        Ajustes aj = new Ajustes(ctx.getExternalFilesDir(null));
        String inyectado = aj.ficheroDePrueba("test_media.json");
        if (inyectado != null) {
            info.permiso = true;
            info.titulo = texto(inyectado, "titulo");
            info.artista = texto(inyectado, "artista");
            info.album = texto(inyectado, "album");
            info.sonando = "true".equals(crudo(inyectado, "sonando"));
            return info;
        }

        MediaController mc = activo(ctx);
        if (mc == null) {
            // Distinguir "no hay permiso" de "no hay nada sonando" importa: son dos
            // problemas distintos y se arreglan de forma distinta.
            info.permiso = permisoConcedido(ctx);
            return info;
        }

        info.permiso = true;
        try {
            MediaMetadata md = mc.getMetadata();
            if (md != null) {
                info.titulo = metadato(md, MediaMetadata.METADATA_KEY_TITLE);
                info.artista = metadato(md, MediaMetadata.METADATA_KEY_ARTIST);
                info.album = metadato(md, MediaMetadata.METADATA_KEY_ALBUM);
                info.caratula = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                if (info.caratula == null) info.caratula = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
            }
            PlaybackState ps = mc.getPlaybackState();
            info.sonando = ps != null && ps.getState() == PlaybackState.STATE_PLAYING;
        } catch (Throwable t) {
            info.permiso = permisoConcedido(ctx);
        }
        return info;
    }

    /** Controla el reproductor activo: "anterior", "siguiente" o "alternar". */
    static void control(Context ctx, String accion) {
        MediaController mc = activo(ctx);
        if (mc == null) return;
        try {
            MediaController.TransportControls c = mc.getTransportControls();
            if ("anterior".equals(accion)) {
                c.skipToPrevious();
            } else if ("siguiente".equals(accion)) {
                c.skipToNext();
            } else {
                // "alternar" lo decide quien llama a partir del estado: aquí solo se
                // llega con la intención clara, para no pelearse con el reproductor.
                c.play();
            }
        } catch (Throwable ignored) {
            // Que el reproductor no responda no puede tumbar la pantalla de inicio.
        }
    }

    /** Pausa o reanuda según lo que esté haciendo ahora mismo. */
    static void alternar(Context ctx) {
        MediaController mc = activo(ctx);
        if (mc == null) return;
        try {
            MediaController.TransportControls c = mc.getTransportControls();
            PlaybackState ps = mc.getPlaybackState();
            if (ps != null && ps.getState() == PlaybackState.STATE_PLAYING) {
                c.pause();
            } else {
                c.play();
            }
        } catch (Throwable ignored) {
        }
    }

    /** El controlador de la sesión activa, o null si no hay ninguna o falta permiso. */
    private static MediaController activo(Context ctx) {
        try {
            MediaSessionManager msm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (msm == null) return null;
            List<MediaController> sesiones = msm.getActiveSessions(
                    new ComponentName(ctx, MediaListener.class));
            if (sesiones == null || sesiones.isEmpty()) return null;
            return sesiones.get(0);      // la primera activa es la que suena
        } catch (SecurityException e) {
            return null;                 // sin permiso de notificaciones
        } catch (Throwable t) {
            return null;
        }
    }

    /** ¿Tenemos concedido el acceso a las notificaciones? */
    static boolean permisoConcedido(Context ctx) {
        try {
            String paquete = ctx.getPackageName();
            String lista = Settings.Secure.getString(ctx.getContentResolver(),
                    "enabled_notification_listeners");
            return lista != null && lista.contains(paquete);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Abre los ajustes donde se concede el acceso a las notificaciones. */
    static void ajustesNotificaciones(Context ctx) {
        try {
            Intent i = new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Throwable t) {
            try {
                Intent i = new Intent(Settings.ACTION_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
            } catch (Throwable ignored) {
            }
        }
    }

    private static String metadato(MediaMetadata md, String clave) {
        CharSequence cs = md.getText(clave);
        if (cs == null) return null;
        String v = cs.toString().trim();
        return v.length() == 0 ? null : v;
    }

    private static String crudo(String todo, String campo) {
        int i = todo.indexOf("\"" + campo + "\"");
        if (i < 0) return null;
        int d = todo.indexOf(':', i);
        if (d < 0) return null;
        int a = d + 1;
        while (a < todo.length() && Character.isWhitespace(todo.charAt(a))) a++;
        int b = a;
        while (b < todo.length() && ",}\n \t".indexOf(todo.charAt(b)) < 0) b++;
        return todo.substring(a, b).trim();
    }

    private static String texto(String todo, String campo) {
        int i = todo.indexOf("\"" + campo + "\"");
        if (i < 0) return null;
        int d = todo.indexOf(':', i);
        if (d < 0) return null;
        int a = todo.indexOf('"', d);
        if (a < 0) return null;
        int b = todo.indexOf('"', a + 1);
        if (b < 0) return null;
        return todo.substring(a + 1, b);
    }
}
