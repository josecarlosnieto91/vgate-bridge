/* Pantalla de inicio de Polar Star — launcher de cuadro de mandos.

   Los instrumentos se DIBUJAN aquí: un arco, sus marcas de escala, los números y
   el valor. No hay ningún arco escrito a mano en el HTML ni valores pintados en
   el CSS: todo sale de la escala de cada magnitud (mínimo, máximo y umbrales).

   DOS MODOS, a propósito:
   - CON puente (en la tablet): `window.Android.estado()` y `media()` devuelven
     los datos reales del coche y del reproductor.
   - SIN puente (en un navegador): datos simulados y marcados como tales. Así se
     revisa la pantalla sin tablet y sin coche, y es LA MISMA pantalla.

   Regla que no se rompe: un dato que no llega se queda en "—" y su arco vacío,
   nunca en 0. Un cuadro que marca cero cuando no sabe nada es una mentira
   peligrosa.

   Compatibilidad: WebView de Android 10 = Chromium 74. Sin flechas de función,
   sin plantillas de texto, sin `let`/`const`, sin `aspect-ratio`. Y para crear
   SVG hay que usar createElementNS, no createElement. */

(function () {
  'use strict';

  var hayPuente = !!(window.Android && typeof window.Android.estado === 'function');
  var SVG = 'http://www.w3.org/2000/svg';

  // Umbrales reales, de ~/.hermes/scripts/obd_vehicle_config.json (los mismos que
  // usa la telemetría). Si cambian allí, cambian aquí: están en un sitio.
  var UMBRALES = {
    velocidadAlta: 120,
    regimenRojo: 4800,
    refrigeranteAviso: 95,
    refrigeranteCritico: 105,
    combustibleBajo: 15
  };

  var $ = function (id) { return document.getElementById(id); };

  // ── Dibujo de un instrumento ─────────────────────────────────────────────
  /* cfg: {min, max, barrido, paso, unidad, decimales, zonaRoja, invertir}
     Devuelve {poner(v)} para actualizar el valor. */
  function crearInstrumento(contenedor, cfg) {
    if (!contenedor) return { poner: function () {} };
    var LADO = 200, CX = 100, CY = 100;
    var RADIO = 78, GROSOR = 9;
    var inicio = 90 + (360 - cfg.barrido) / 2;      // centrado arriba
    var fin = inicio + cfg.barrido;
    var largo = (cfg.max - cfg.min) || 1;

    var svg = document.createElementNS(SVG, 'svg');
    svg.setAttribute('viewBox', '0 0 ' + LADO + ' ' + LADO);
    svg.setAttribute('preserveAspectRatio', 'xMidYMid meet');
    contenedor.appendChild(svg);

    function punto(grados, radio) {
      var rad = grados * Math.PI / 180;
      return { x: CX + radio * Math.cos(rad), y: CY + radio * Math.sin(rad) };
    }
    function arco(desde, hasta, radio) {
      var a = punto(desde, radio), b = punto(hasta, radio);
      var grande = (hasta - desde) > 180 ? 1 : 0;
      return 'M ' + a.x.toFixed(2) + ' ' + a.y.toFixed(2) +
             ' A ' + radio + ' ' + radio + ' 0 ' + grande + ' 1 ' + b.x.toFixed(2) + ' ' + b.y.toFixed(2);
    }
    function nodo(nombre, atributos) {
      var n = document.createElementNS(SVG, nombre);
      for (var k in atributos) { if (atributos.hasOwnProperty(k)) n.setAttribute(k, atributos[k]); }
      return n;
    }

    // aro
    svg.appendChild(nodo('path', {
      d: arco(inicio, fin, RADIO), 'class': 'aro', 'stroke-width': GROSOR,
      'stroke-linecap': 'round'
    }));
    // zona roja (si la hay): se pinta en el tramo final antes que el valor, para
    // que el arco de valor quede por encima cuando llegue ahí.
    if (cfg.zonaRoja !== undefined) {
      var gRojo = cfg.min + largo * cfg.zonaRoja;
      if (gRojo < cfg.max) {
        svg.appendChild(nodo('path', {
          d: arco(inicio + cfg.barrido * cfg.zonaRoja, fin, RADIO),
          'class': 'aro-rojo', 'stroke-width': GROSOR
        }));
      }
    }
    // marcas de escala
    var pasos = Math.round(largo / cfg.paso);
    for (var i = 0; i <= pasos; i++) {
      var frac = i / pasos;
      var ang = inicio + cfg.barrido * frac;
      var largoMarca = (i % cfg.cadaCuantas === 0);
      var a1 = punto(ang, RADIO - GROSOR / 2 - 3);
      var a2 = punto(ang, RADIO - GROSOR / 2 - (largoMarca ? 14 : 8));
      svg.appendChild(nodo('line', {
        x1: a1.x.toFixed(1), y1: a1.y.toFixed(1), x2: a2.x.toFixed(1), y2: a2.y.toFixed(1),
        'class': largoMarca ? 'marca-larga' : 'marca'
      }));
      if (largoMarca && cfg.rotular) {
        var p = punto(ang, RADIO - 34);
        var t = nodo('text', {
          x: p.x.toFixed(1), y: (p.y + 4).toFixed(1), 'class': 'escala', 'text-anchor': 'middle'
        });
        // divisor: el régimen se rotula 1,2,3… en vez de 1000,2000,3000…
        // Así lo hace cualquier cuadro de verdad.
        t.textContent = String(Math.round((cfg.min + largo * frac) / (cfg.divisor || 1)));
        svg.appendChild(t);
      }
    }
    // arco de valor
    var arcoValor = nodo('path', {
      d: arco(inicio, fin, RADIO), 'class': 'arco-valor', 'stroke-width': GROSOR,
      'stroke-linecap': 'round'
    });
    svg.appendChild(arcoValor);
    var largoTotal = arcoValor.getTotalLength ? arcoValor.getTotalLength() : 400;
    arcoValor.setAttribute('stroke-dasharray', largoTotal);
    arcoValor.setAttribute('stroke-dashoffset', largoTotal);

    // valor y unidad en el centro
    var tamano = cfg.tamano || 46;
    var texto = nodo('text', {
      x: CX, y: CY + tamano * 0.22, 'class': 'valor', 'text-anchor': 'middle',
      'font-size': tamano
    });
    texto.textContent = '—';
    svg.appendChild(texto);
    var etiqueta = nodo('text', {
      x: CX, y: CY + tamano * 0.22 + 17, 'class': 'unidad', 'text-anchor': 'middle', 'font-size': 14
    });
    etiqueta.textContent = cfg.unidad || '';
    svg.appendChild(etiqueta);

    return {
      poner: function (valor) {
        var sinDato = (valor === null || valor === undefined);
        if (sinDato) {
          texto.textContent = '—';
          arcoValor.setAttribute('stroke-dashoffset', largoTotal);
          svg.classList.remove('en-rojo');
          return;
        }
        var v = Math.min(Math.max(valor, cfg.min), cfg.max);
        var frac = (v - cfg.min) / largo;
        texto.textContent = cfg.decimales ? valor.toFixed(cfg.decimales) : String(Math.round(valor));
        arcoValor.setAttribute('stroke-dashoffset', (largoTotal * (1 - frac)).toFixed(1));
        if (cfg.zonaRoja !== undefined) {
          svg.classList.toggle('en-rojo', frac >= cfg.zonaRoja);
        }
      }
    };
  }

  // ── Los cuatro instrumentos, con su escala ───────────────────────────────
  var medidorVelocidad = crearInstrumento($('med-vel'), {
    min: 0, max: 180, barrido: 240, paso: 20, cadaCuantas: 1,
    unidad: 'km/h', tamano: 52, rotular: true, zonaRoja: UMBRALES.velocidadAlta / 180
  });
  var medidorRegimen = crearInstrumento($('med-rpm'), {
    min: 0, max: 6000, barrido: 240, paso: 1000, cadaCuantas: 1, divisor: 1000,
    unidad: 'rpm', tamano: 30, rotular: true, zonaRoja: UMBRALES.regimenRojo / 6000
  });
  var medidorCombustible = crearInstrumento($('med-fuel'), {
    min: 0, max: 100, barrido: 200, paso: 50, cadaCuantas: 2,
    unidad: '%', tamano: 26, rotular: true, zonaRoja: 0.85
  });
  var medidorMotor = crearInstrumento($('med-temp'), {
    min: 40, max: 120, barrido: 200, paso: 40, cadaCuantas: 2,
    unidad: '°C', tamano: 26, rotular: true,
    zonaRoja: (UMBRALES.refrigeranteAviso - 40) / 80
  });

  // ── Reloj (local, sin red; refresco por minuto) ──────────────────────────
  var DIAS = ['domingo', 'lunes', 'martes', 'miércoles', 'jueves', 'viernes', 'sábado'];
  var MESES = ['enero', 'febrero', 'marzo', 'abril', 'mayo', 'junio', 'julio',
               'agosto', 'septiembre', 'octubre', 'noviembre', 'diciembre'];
  function reloj() {
    var d = new Date();
    var hh = d.getHours(), mm = d.getMinutes();
    var e = $('reloj-hora');
    if (e) e.textContent = (hh < 10 ? '0' : '') + hh + ':' + (mm < 10 ? '0' : '') + mm;
    var f = $('reloj-fecha');
    if (f) f.textContent = DIAS[d.getDay()] + ', ' + d.getDate() + ' de ' + MESES[d.getMonth()];
  }

  // ── Música ───────────────────────────────────────────────────────────────
  function pintarMusica(m) {
    var zona = $('m-zona'), aviso = $('m-aviso');
    if (!zona) return;
    if (m && m.permiso === false) {
      zona.classList.add('plegada');
      $('m-titulo').textContent = 'Música';
      $('m-artista').textContent = '';
      aviso.textContent = 'Permite el acceso a notificaciones para ver lo que suena';
      aviso.classList.add('enlace');
      aviso.onclick = function () { abrirAjusteNotificaciones(); };
      return;
    }
    aviso.classList.remove('enlace');
    aviso.onclick = null;
    if (!m || !m.titulo) {
      zona.classList.add('plegada');
      $('m-titulo').textContent = 'Sin reproducción';
      $('m-artista').textContent = '';
      aviso.textContent = '';
      return;
    }
    zona.classList.remove('plegada');
    $('m-titulo').textContent = m.titulo;
    $('m-artista').textContent = m.artista || '';
    aviso.textContent = m.sonando ? '' : 'en pausa';
    var icono = $('m-play-icono');
    if (icono) {
      icono.innerHTML = m.sonando
        ? '<path d="M8 5h3v14H8zM13 5h3v14h-3z"/>'
        : '<path d="M7 4l13 8-13 8z"/>';
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

  // ── Datos del coche a los instrumentos ───────────────────────────────────
  function pintarNumero(el, valor, decimales) {
    if (!el) return;
    if (valor === null || valor === undefined) { el.textContent = '—'; return; }
    el.textContent = decimales ? valor.toFixed(decimales) : String(Math.round(valor));
  }
  function pintarTestigo(el, textoEl, activo, etiqueta) {
    if (!el) return;
    el.classList.toggle('activo', activo === true);
    el.classList.toggle('desconocido', activo === null || activo === undefined);
    if (textoEl) textoEl.textContent = etiqueta;
  }

  function pintar(estado, simulado) {
    medidorVelocidad.poner(estado.speed);
    medidorRegimen.poner(estado.rpm);
    medidorCombustible.poner(estado.fuel);
    medidorMotor.poner(estado.coolant);

    var av = $('vel-aviso');
    if (av) {
      av.textContent = (estado.coolant !== null && estado.coolant !== undefined &&
                        estado.coolant >= UMBRALES.refrigeranteCritico) ? 'motor caliente' : '';
      if (estado.speed !== null && estado.speed !== undefined && estado.speed > UMBRALES.velocidadAlta) {
        av.textContent = 'velocidad alta';
      }
      if (estado.fuel !== null && estado.fuel !== undefined && estado.fuel < UMBRALES.combustibleBajo) {
        av.textContent = 'combustible bajo';
      }
      if (simulado) av.textContent = 'datos simulados (sin puente)';
    }

    pintarNumero($('d-consumo'), estado.consumption, 1);
    pintarNumero($('d-rango'), estado.range, 0);
    pintarNumero($('d-ext'), estado.outsideTemp, 0);

    pintarTestigo($('t-puertas'), $('t-puertas-texto'), estado.doorOpen,
                  estado.doorOpen ? 'puerta abierta' : 'puertas');
    pintarTestigo($('t-luces'), $('t-luces-texto'), estado.lightsOn,
                  estado.lightsOn ? 'luces puestas' : 'alumbrado');

    tema(estado);
  }

  /* Tema automático por el ALUMBRADO del coche (CAN 1281/56), no por la hora: con
     niebla a mediodía llevas luces y quieres el cuadro oscuro. */
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
      // innerHTML SOLO con las constantes de ICONOS (SVG dibujado aquí dentro). El
      // nombre, que sí puede venir del fichero de config, se pinta con textContent.
      b.innerHTML = '<svg viewBox="0 0 24 24">' + (ICONOS[app.icono] || ICONOS.mapa) + '</svg><span></span>';
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

  // ── Refresco: 1 Hz para los instrumentos; el reloj, por su cuenta ────────
  function refrescar() {
    if (hayPuente) {
      try {
        pintar(JSON.parse(window.Android.estado()), false);
      } catch (e) {
        var av = $('vel-aviso'); if (av) av.textContent = 'estado ilegible';
      }
      try {
        pintarMusica(window.Android.media ? JSON.parse(window.Android.media()) : null);
      } catch (e) { pintarMusica(null); }
    } else {
      pintar(simulado(), true);
      pintarMusica(simuladoMedia());
    }
  }

  var t = 0;
  function simulado() {
    t = (t + 1) % 8;
    return {
      speed: t === 7 ? null : 34 + t * 9,
      rpm: 1500 + t * 260,
      coolant: 84 + t,
      consumption: 5.1 + (t % 3) * 0.4,
      range: 612,
      outsideTemp: 27,
      fuel: 62 - t,
      doorOpen: t === 6,
      lightsOn: t >= 4
    };
  }
  function simuladoMedia() {
    return { titulo: 'Bohemian Rhapsody', artista: 'Queen', sonando: t % 2 === 0 };
  }

  reloj();
  montarAccesos();
  refrescar();
  setInterval(refrescar, 1000);
  setInterval(reloj, 20000);
})();
