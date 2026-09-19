/* Lógica de la pantalla de inicio de Polar Star.

   DOS MODOS, a propósito:
   - CON puente (en la tablet): `window.Android.estado()` y `window.Android.media()`
     devuelven el JSON real (coche y reproductor).
   - SIN puente (en un navegador cualquiera): datos simulados y marcados como
     tales. Así la pantalla se monta y se revisa sin tablet y sin coche, y lo que
     se ve en el navegador es LA MISMA pantalla, no una maqueta aparte que se
     desincroniza.

   Regla que no se rompe: un dato que no llega se pinta "—", nunca 0. Un
   velocímetro marcando cero cuando no sabe nada es una mentira peligrosa.

   Compatibilidad: el WebView de Android 10 es Chromium 74. Nada de flechas
   (arrow functions), plantillas de texto ni `gap` en flex. */

(function () {
  'use strict';

  var hayPuente = !!(window.Android && typeof window.Android.estado === 'function');

  // Accesos por defecto: paquetes comprobados en la tablet el 2026-09-19.
  // Editables sin recompilar: llegan del fichero launcher.json vía el puente.
  var ACCESOS = [
    { nombre: 'Maps',     paquete: 'com.google.android.apps.maps', icono: 'mapa' },
    { nombre: 'Waze',     paquete: 'com.waze',                     icono: 'mapa' },
    { nombre: 'Spotify',  paquete: 'com.spotify.music',            icono: 'musica' },
    { nombre: 'Radio',    paquete: 'com.tw.radio',                 icono: 'radio' },
    { nombre: 'Android Auto', paquete: 'com.zjinnova.zlink',       icono: 'auto' }
  ];

  var ICONOS = {
    mapa:   '<path d="M12 21s-6-5.2-6-10a6 6 0 1 1 12 0c0 4.8-6 10-6 10z"/><circle cx="12" cy="11" r="2.2"/>',
    musica: '<circle cx="7" cy="17" r="2.6"/><circle cx="17" cy="15" r="2.6"/><path d="M9.6 17V7l9.4-2v10"/>',
    radio:  '<circle cx="12" cy="13" r="3"/><path d="M4.5 5.5a10 10 0 0 0 0 15M19.5 5.5a10 10 0 0 1 0 15M7.5 8.5a6 6 0 0 0 0 9M16.5 8.5a6 6 0 0 1 0 9"/>',
    auto:   '<rect x="3" y="4" width="18" height="12" rx="2"/><path d="M7 20h10M12 16v4"/><path d="M12 8l2.5 2.5L12 13"/>'
  };

  var $ = function (id) { return document.getElementById(id); };

  // ── Pintar números y testigos ────────────────────────────────────────────
  function pintarNumero(el, valor, decimales) {
    if (el === null) return;
    if (valor === null || valor === undefined) { el.textContent = '—'; return; }
    el.textContent = decimales ? valor.toFixed(decimales) : Math.round(valor);
  }

  function pintarTestigo(el, textoEl, activo, etiqueta) {
    if (!el) return;
    el.classList.toggle('activo', activo === true);
    el.classList.toggle('desconocido', activo === null || activo === undefined);
    if (textoEl) textoEl.textContent = etiqueta;
  }

  // ── Reloj ────────────────────────────────────────────────────────────────
  // Local, sin red. Se refresca por minuto: en un SoC flojo no compensa más y
  // nadie necesita ver correr los segundos.
  var DIAS = ['domingo', 'lunes', 'martes', 'miércoles', 'jueves', 'viernes', 'sábado'];
  var MESES = ['enero', 'febrero', 'marzo', 'abril', 'mayo', 'junio', 'julio',
               'agosto', 'septiembre', 'octubre', 'noviembre', 'diciembre'];
  function reloj() {
    var d = new Date();
    var hh = d.getHours(), mm = d.getMinutes();
    var hora = (hh < 10 ? '0' : '') + hh + ':' + (mm < 10 ? '0' : '') + mm;
    var el = $('reloj-hora'); if (el) el.textContent = hora;
    var f = $('reloj-fecha');
    if (f) f.textContent = DIAS[d.getDay()] + ', ' + d.getDate() + ' de ' + MESES[d.getMonth()];
  }

  // ── Música ───────────────────────────────────────────────────────────────
  /* Tres estados, y los tres se ven:
      1. reproduciendo        → título, artista y controles
      2. nada sonando         → la zona se pliega (no ocupa sitio en balde)
      3. sin permiso          → aviso con enlace para concederlo en Ajustes
     El caso 3 es real: leer el reproductor exige "acceso a notificaciones", que se
     concede a mano. Un bloque vacío y mudo sería lo peor. */
  function pintarMusica(m) {
    var zona = $('m-zona');
    if (!zona) return;
    var aviso = $('m-aviso');
    var titulo = $('m-titulo'), artista = $('m-artista');

    if (m && m.permiso === false) {
      zona.classList.add('plegada');
      titulo.textContent = 'Música';
      artista.textContent = '';
      aviso.textContent = 'Permite el acceso a notificaciones para ver lo que suena';
      aviso.classList.add('enlace');
      aviso.onclick = function () { abrirAjusteNotificaciones(); };
      return;
    }
    aviso.classList.remove('enlace');
    aviso.onclick = null;

    if (!m || !m.titulo) {
      zona.classList.add('plegada');
      titulo.textContent = 'Sin reproducción';
      artista.textContent = '';
      aviso.textContent = '';
      return;
    }
    zona.classList.remove('plegada');
    titulo.textContent = m.titulo;
    artista.textContent = m.artista || '';
    aviso.textContent = m.sonando ? '' : 'en pausa';
    var icono = $('m-play-icono');
    if (icono) {
      icono.innerHTML = m.sonando
        ? '<path d="M8 5h3v14H8zM13 5h3v14h-3z"/>'      // pausa
        : '<path d="M7 4l13 8-13 8z"/>';                // play
    }
  }

  function abrirAjusteNotificaciones() {
    if (window.Android && typeof window.Android.ajustesNotificaciones === 'function') {
      window.Android.ajustesNotificaciones();
    } else {
      alert('Ajustes → Acceso a notificaciones: actívalo para VgateBridge.');
    }
  }

  function control(accion) {
    if (window.Android && typeof window.Android.mediaControl === 'function') {
      window.Android.mediaControl(accion);
    }
  }

  // ── Estado del coche ─────────────────────────────────────────────────────
  function pintar(estado, simulado) {
    var v = estado.speed;
    var vel = $('vel-valor');
    pintarNumero(vel, v, 0);
    vel.classList.toggle('sin-dato', v === null || v === undefined);
    var av = $('vel-aviso');
    av.textContent = (v !== null && v !== undefined && v > 120) ? 'Velocidad alta' : '';
    if (simulado) av.textContent = 'datos simulados (sin puente)';
    if (v === null || v === undefined) av.textContent = 'sin datos del motor';

    pintarNumero($('d-consumo'), estado.consumption, 1);
    pintarNumero($('d-rango'), estado.range, 0);
    pintarNumero($('d-ext'), estado.outsideTemp, 0);
    pintarNumero($('d-motor'), estado.coolant, 0);
    pintarNumero($('d-rpm'), estado.rpm, 0);
    pintarNumero($('d-fuel'), estado.fuel, 0);

    pintarTestigo($('t-puertas'), $('t-puertas-texto'), estado.doorOpen,
                  estado.doorOpen ? 'puerta abierta' : 'puertas');
    pintarTestigo($('t-luces'), $('t-luces-texto'), estado.lightsOn,
                  estado.lightsOn ? 'luces puestas' : 'alumbrado');

    tema(estado);
  }

  /* Tema: en automático lo decide el ALUMBRADO (dato del CAN), no la hora. Con
     niebla a mediodía llevas luces y quieres el panel oscuro. */
  function tema(estado) {
    var modo = (window.Android && window.Android.tema) ? window.Android.tema() : 'auto';
    if (modo === 'dia' || modo === 'noche') { aplicar(modo); return; }
    if (estado.lightsOn === true)  { aplicar('noche'); return; }
    if (estado.lightsOn === false) { aplicar('dia');   return; }
    var h = new Date().getHours();
    aplicar((h >= 8 && h < 20) ? 'dia' : 'noche');
  }

  function aplicar(clase) {
    var b = document.body;
    if (b.classList.contains(clase)) return;
    b.classList.remove('dia', 'noche');
    b.classList.add(clase);
  }

  // ── Accesos ──────────────────────────────────────────────────────────────
  function montarAccesos() {
    var caja = $('accesos');
    if (!caja) return;
    var lista = ACCESOS;
    if (window.Android && typeof window.Android.accesos === 'function') {
      try {
        var crudo = window.Android.accesos();
        if (crudo) lista = JSON.parse(crudo);
      } catch (e) { /* config rota: se usan los de fábrica, nunca se queda vacío */ }
    }
    caja.innerHTML = '';
    lista.forEach(function (app) {
      var b = document.createElement('button');
      // innerHTML SOLO con las constantes de ICONOS (SVG dibujado aquí dentro):
      // no entra ningún dato de fuera. El nombre, que sí puede venir del fichero
      // de config, se pinta con textContent justo debajo. No hay inyección.
      b.innerHTML = '<svg viewBox="0 0 24 24">' + (ICONOS[app.icono] || ICONOS.mapa) + '</svg>'
                  + '<span></span>';
      b.querySelector('span').textContent = app.nombre;
      if (!hayPuente) b.classList.add('no-disponible');
      b.addEventListener('click', function () {
        if (window.Android && typeof window.Android.abrirApp === 'function') {
          window.Android.abrirApp(app.paquete);
        }
      });
      caja.appendChild(b);
    });
  }

  var btnAjustes = $('btn-ajustes');
  if (btnAjustes) {
    btnAjustes.addEventListener('click', function () {
      if (window.Android && typeof window.Android.ajustesInicio === 'function') {
        window.Android.ajustesInicio();
      } else {
        alert('Ajustes → Aplicación de inicio: desde ahí se vuelve al launcher de la ROM.');
      }
    });
  }
  var bPrev = $('m-prev'), bPlay = $('m-play'), bNext = $('m-next');
  if (bPrev) bPrev.addEventListener('click', function () { control('anterior'); });
  if (bPlay) bPlay.addEventListener('click', function () { control('alternar'); });
  if (bNext) bNext.addEventListener('click', function () { control('siguiente'); });

  // ── Bucle de refresco ────────────────────────────────────────────────────
  // 1 Hz para el coche (es lo que tarda como mucho el velocímetro en reflejar un
  // cambio). El reloj va por su cuenta, una vez por minuto.
  function refrescar() {
    if (hayPuente) {
      try {
        pintar(JSON.parse(window.Android.estado()), false);
      } catch (e) {
        $('vel-aviso').textContent = 'estado ilegible';   // se ve, no se oculta
      }
      try {
        pintarMusica(window.Android.media ? JSON.parse(window.Android.media()) : null);
      } catch (e) {
        pintarMusica(null);
      }
    } else {
      pintar(simulado(), true);
      pintarMusica(simuladoMedia());
    }
  }

  /* Datos de prueba para el navegador. La velocidad solo se cae en un ciclo de
     ocho, a propósito: así se revisa la pantalla NORMAL y también el caso "sin
     dato", sin que la captura caiga casi siempre en el raro. */
  var t = 0;
  function simulado() {
    t = (t + 1) % 8;
    return {
      speed: t === 7 ? null : 47 + t * 9,
      rpm: 1450 + t * 120,
      coolant: 84,
      consumption: 4.4 + (t % 3) * 0.3,
      range: 612,
      outsideTemp: 17,
      fuel: 62,
      doorOpen: t === 6,
      lightsOn: t >= 4
    };
  }
  function simuladoMedia() {
    return { titulo: 'Nothing Else Matters', artista: 'Metallica', sonando: t % 2 === 0 };
  }

  reloj();
  montarAccesos();
  refrescar();
  setInterval(refrescar, 1000);
  setInterval(reloj, 20000);        // cada 20 s: de sobra para cambiar de minuto
})();
