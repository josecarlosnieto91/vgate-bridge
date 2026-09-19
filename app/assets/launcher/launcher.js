/* Lógica de la pantalla de inicio.

   DOS MODOS, a propósito:
   - CON puente (en la tablet): `window.Android.estado()` devuelve el JSON del
     estado real del coche (lo sirve la tarea 7 del plan).
   - SIN puente (en un navegador cualquiera): se usan datos simulados y marcados
     como tales. Así la pantalla se puede montar y revisar en Cassiopeia, sin
     tablet y sin coche. Lo que se ve en el navegador es la pantalla de verdad,
     no una maqueta aparte que se desincroniza.

   Nada de ceros inventados: si un dato no llega, se pinta "—" y la velocidad se
   muestra apagada. Es la misma regla que en LiveState.java. */

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

  function pintarNumero(el, valor, decimales) {
    if (el === null) return;
    if (valor === null || valor === undefined) {
      el.textContent = '—';                       // no es 0: es "no lo sé"
      return;
    }
    el.textContent = decimales ? valor.toFixed(decimales) : Math.round(valor);
  }

  function pintarTestigo(el, activo, etiqueta) {
    if (!el) return;
    el.classList.toggle('activo', activo === true);
    el.classList.toggle('desconocido', activo === null || activo === undefined);
    var t = el.querySelector('span');
    if (t) t.textContent = etiqueta;
  }

  function pintar(estado, simulado) {
    var v = estado.speed;
    var vel = $('vel-valor');
    pintarNumero(vel, v, 0);
    vel.classList.toggle('sin-dato', v === null || v === undefined);
    $('vel-aviso').textContent = (v !== null && v > 120) ? 'Velocidad alta' : '';
    if (simulado) $('vel-aviso').textContent = 'datos simulados (sin puente)';
    // Sin velocidad: se dice por qué, en vez de dejar un guion mudo.
    if (v === null || v === undefined) $('vel-aviso').textContent = 'sin datos del motor';

    pintarNumero($('d-consumo'), estado.consumption, 1);
    pintarNumero($('d-rango'), estado.range, 0);
    pintarNumero($('d-ext'), estado.outsideTemp, 0);
    pintarNumero($('d-motor'), estado.coolant, 0);
    pintarNumero($('d-rpm'), estado.rpm, 0);
    pintarNumero($('d-fuel'), estado.fuel, 0);

    pintarTestigo($('t-puertas'), estado.doorOpen, estado.doorOpen ? 'Puerta abierta' : 'Puertas');
    pintarTestigo($('t-luces'), estado.lightsOn, estado.lightsOn ? 'Luces puestas' : 'Alumbrado');

    tema(estado);
  }

  /* Tema: en automático lo decide el ALUMBRADO del coche (dato del CAN), no la
     hora. Si no hay dato de luces, se cae a la hora como respaldo. */
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

  function montarAccesos() {
    var caja = $('accesos');
    if (!caja) return;
    var lista = ACCESOS;
    if (window.Android && typeof window.Android.accesos === 'function') {
      try {
        var crudo = window.Android.accesos();
        if (crudo) lista = JSON.parse(crudo);
      } catch (e) { /* si el fichero de config está roto, se usan los de fábrica */ }
    }
    caja.innerHTML = '';
    lista.forEach(function (app) {
      var b = document.createElement('button');
      // innerHTML SOLO con las constantes de ICONOS (SVG dibujado aquí dentro):
      // no entra ningún dato de fuera. El nombre de la app, que sí puede venir del
      // fichero de config, se pinta con textContent justo debajo. No hay inyección
      // posible; se deja así por claridad, no por descuido.
      b.innerHTML = '<svg viewBox="0 0 24 24">' + (ICONOS[app.icono] || ICONOS.mapa) + '</svg>'
                  + '<span></span>';
      b.querySelector('span').textContent = app.nombre;
      // En el navegador no hay apps que abrir: se desactiva en vez de fingir.
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
        alert('Desde aquí se abre Ajustes → Aplicación de inicio para volver al launcher de la ROM.');
      }
    });
  }

  /* Bucle de refresco: 1 Hz. Es lo que tarda como mucho el velocímetro en
     reflejar un cambio, y no supone carga apreciable para el SoC. */
  function refrescar() {
    if (hayPuente) {
      try {
        pintar(JSON.parse(window.Android.estado()), false);
      } catch (e) {
        $('vel-aviso').textContent = 'estado ilegible';   // se ve, no se oculta
      }
    } else {
      pintar(simulado(), true);
    }
  }

  /* Datos de prueba para el navegador. La velocidad solo se cae en un ciclo de
     ocho, a propósito: así se revisa la pantalla NORMAL (con número grande) y
     también el caso "sin dato", sin que la captura caiga casi siempre en el raro. */
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

  montarAccesos();
  refrescar();
  setInterval(refrescar, 1000);
})();
