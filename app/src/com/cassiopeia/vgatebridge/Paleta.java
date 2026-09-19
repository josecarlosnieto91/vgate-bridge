package com.cassiopeia.vgatebridge;

/**
 * Colores de la pantalla, en sus dos temas.
 *
 * Son los MISMOS valores que usaba la versión HTML (assets/launcher/launcher.css): la
 * identidad visual no cambia al pasar a vistas nativas. Están escritos aquí, y no en
 * recursos XML, porque el proyecto no tiene carpeta res/ y no la va a tener por esto.
 *
 * El tema no lo decide la hora: lo decide el ALUMBRADO DEL COCHE. Con niebla a
 * mediodía el cuadro se oscurece, que es lo que hace un cuadro de verdad.
 */
final class Paleta {

    final int fondo;      // el fondo de la pantalla
    final int aro;        // el aro del instrumento
    final int marca;      // las marcas de la escala
    final int sup;        // superficie de las tarjetas
    final int sec;        // texto secundario
    final int apagado;    // texto y trazos apagados
    final int acento;     // el color de la aguja y del valor
    final int alerta;     // solo para avisos: nunca decorativo
    final int verde;      // correcto / eficiente
    final int borde;      // separaciones finas

    private Paleta(int fondo, int aro, int marca, int sup, int sec, int apagado,
                   int acento, int alerta, int verde, int borde) {
        this.fondo = fondo; this.aro = aro; this.marca = marca; this.sup = sup;
        this.sec = sec; this.apagado = apagado; this.acento = acento;
        this.alerta = alerta; this.verde = verde; this.borde = borde;
    }

    /** Noche: casi negro, para no deslumbrar. Nunca negro puro: cansa la vista. */
    static Paleta noche() {
        return new Paleta(0xFF080B10, 0xFF1D2631, 0xFF33404F, 0xFF111823,
                0xFF93A3B5, 0xFF4A5665, 0xFFFFAB00, 0xFFFF5A3C, 0xFF38D39F,
                0x14FFFFFF);
    }

    /** Día: gris muy claro, nunca blanco puro, por el mismo motivo al contrario. */
    static Paleta dia() {
        return new Paleta(0xFFE8ECF0, 0xFFCFD7DF, 0xFF9AA7B3, 0xFFFFFFFF,
                0xFF586675, 0xFF96A3B0, 0xFFE0670A, 0xFFD0331A, 0xFF0F9E73,
                0x140C131A);
    }

    static Paleta de(boolean noche) {
        return noche ? noche() : dia();
    }
}
