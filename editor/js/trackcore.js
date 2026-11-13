/*
 * trackcore.js — Modello del tracciato, geometria e formato GPX.
 *
 * Speculare al core Kotlin dell'app Android (Geo.kt, Gpx.kt, GpxImport.kt):
 * il GPX esportato qui contiene le estensioni "gpstel" (tipo, tolleranza,
 * orientamento e larghezza delle linee) e l'app lo reimporta identico;
 * viceversa l'editor apre i GPX esportati dall'app e i GPX generici.
 *
 * Tracciato:
 *   { name, type: 'CIRCULAR'|'OPEN', tolerance,
 *     start: Gate|null, finish: Gate|null, splits: [{name, gate}],
 *     path: [{lat, lon, ele?}] }
 * Gate (linea di cronometraggio): { lat, lon, heading (gradi, 0 = linea
 *   nord-sud), width (metri) }
 */
(function (root) {
  'use strict';

  const R = 6371008.8; // raggio medio terrestre (m), come nell'app
  const rad = (d) => (d * Math.PI) / 180;
  const deg = (r) => (r * 180) / Math.PI;

  const GPSTEL_NS = 'https://gpstelemetry.app/xmlschemas/track/v1';
  const DEFAULT_TOLERANCE = 15;
  const LOOP_DISTANCE_M = 60;

  /* ------------------------------- Geometria ----------------------------- */

  function haversine(a, b) {
    const dLat = rad(b.lat - a.lat);
    const dLon = rad(b.lon - a.lon);
    const s = Math.sin(dLat / 2) ** 2 + Math.cos(rad(a.lat)) * Math.cos(rad(b.lat)) * Math.sin(dLon / 2) ** 2;
    return 2 * R * Math.asin(Math.min(1, Math.sqrt(s)));
  }

  /** Proiezione locale (metri est/nord) attorno a ref. */
  function toXY(p, ref) {
    return { x: rad(p.lon - ref.lon) * R * Math.cos(rad(ref.lat)), y: rad(p.lat - ref.lat) * R };
  }

  function offset(p, dxEast, dyNorth) {
    return {
      lat: p.lat + deg(dyNorth / R),
      lon: p.lon + deg(dxEast / (R * Math.cos(rad(p.lat)))),
    };
  }

  /** Rotta da a verso b in gradi [0, 360). */
  function bearing(a, b) {
    const { x, y } = toXY(b, a);
    return (deg(Math.atan2(x, y)) + 360) % 360;
  }

  /** Orientamento di una linea in [0, 180): una linea non ha verso. */
  const normalize = (h) => ((h % 180) + 180) % 180;

  /** I due estremi della linea di un cancello. */
  function gateEndpoints(g) {
    const h = rad(g.heading);
    const dx = (Math.sin(h) * g.width) / 2;
    const dy = (Math.cos(h) * g.width) / 2;
    return [offset(g, dx, dy), offset(g, -dx, -dy)];
  }

  function distToSegment(p, a, b) {
    const P = toXY(p, a);
    const B = toXY(b, a);
    const len2 = B.x * B.x + B.y * B.y;
    if (len2 === 0) return Math.hypot(P.x, P.y);
    const t = Math.max(0, Math.min(1, (P.x * B.x + P.y * B.y) / len2));
    return Math.hypot(P.x - t * B.x, P.y - t * B.y);
  }

  /** Direzione del percorso nel tratto più vicino a p (null senza percorso). */
  function pathHeadingAt(path, p) {
    if (!path || path.length < 2) return null;
    let best = Infinity;
    let h = null;
    for (let i = 0; i < path.length - 1; i++) {
      const d = distToSegment(p, path[i], path[i + 1]);
      if (d < best) {
        best = d;
        h = bearing(path[i], path[i + 1]);
      }
    }
    return h;
  }

  /** Indice del tratto di percorso più vicino a p (per inserire punti). */
  function nearestSegment(path, p) {
    let best = Infinity;
    let idx = -1;
    for (let i = 0; i < path.length - 1; i++) {
      const d = distToSegment(p, path[i], path[i + 1]);
      if (d < best) {
        best = d;
        idx = i;
      }
    }
    return { index: idx, distance: best };
  }

  function pathLength(path) {
    let s = 0;
    for (let i = 1; i < path.length; i++) s += haversine(path[i - 1], path[i]);
    return s;
  }

  /** Linea perpendicolare al percorso nel punto p, larghezza da tolleranza. */
  function defaultGate(p, path, tolerance) {
    const h = pathHeadingAt(path, p);
    return {
      lat: p.lat,
      lon: p.lon,
      heading: h == null ? 0 : normalize(h + 90),
      width: Math.max(20, 2 * (tolerance || DEFAULT_TOLERANCE)),
    };
  }

  /* -------------------------------- Modello ------------------------------ */

  function emptyTrack() {
    return { name: '', type: 'CIRCULAR', tolerance: DEFAULT_TOLERANCE, start: null, finish: null, splits: [], path: [] };
  }

  /** Errori che impediscono l'esportazione (in italiano, per l'interfaccia). */
  function validate(t) {
    const errors = [];
    if (!t.name || !t.name.trim()) errors.push('Dai un nome al tracciato.');
    if (!t.start) errors.push('Manca la linea di partenza.');
    if (t.type === 'OPEN' && !t.finish) errors.push('Tracciato aperto: manca la linea del traguardo.');
    return errors;
  }

  /* --------------------------------- GPX --------------------------------- */

  const esc = (s) => String(s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  const f7 = (n) => Number(n).toFixed(7);
  const f2 = (n) => Number(n).toFixed(2);

  function gateWpt(g, name, role) {
    return `  <wpt lat="${f7(g.lat)}" lon="${f7(g.lon)}"><name>${esc(name)}</name><extensions>` +
      `<gpstel:gate role="${role}" heading="${f2(g.heading)}" width="${f2(g.width)}"/></extensions></wpt>\n`;
  }

  /** GPX 1.1 del tracciato, nello stesso formato dell'app (Gpx.track). */
  function toGpx(t, now = new Date()) {
    let s = '<?xml version="1.0" encoding="UTF-8"?>\n';
    s += '<gpx version="1.1" creator="GPSTelemetry"\n';
    s += '  xmlns="http://www.topografix.com/GPX/1/1"\n';
    s += '  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"\n';
    s += `  xmlns:gpstel="${GPSTEL_NS}"\n`;
    s += '  xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v2"\n';
    s += '  xsi:schemaLocation="http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd">\n';
    s += `  <metadata><name>${esc(t.name)}</name><time>${now.toISOString()}</time>`;
    s += `<extensions><gpstel:track type="${t.type}" tolerance="${Number(t.tolerance).toFixed(1)}"/></extensions></metadata>\n`;
    if (t.start) s += gateWpt(t.start, 'Partenza', 'start');
    if (t.type === 'OPEN' && t.finish) s += gateWpt(t.finish, 'Traguardo', 'finish');
    t.splits.forEach((sp, i) => { s += gateWpt(sp.gate, sp.name || `Parziale ${i + 1}`, 'split'); });
    if (t.path.length) {
      s += `  <trk><name>${esc(t.name)}</name><trkseg>\n`;
      for (const p of t.path) {
        s += `    <trkpt lat="${f7(p.lat)}" lon="${f7(p.lon)}">`;
        if (p.ele != null) s += `<ele>${Number(p.ele).toFixed(1)}</ele>`;
        s += '</trkpt>\n';
      }
      s += '  </trkseg></trk>\n';
    }
    s += '</gpx>\n';
    return s;
  }

  const START_NAMES = new Set(['start', 'partenza', 'via', 'start/finish', 'start / finish']);
  const FINISH_NAMES = new Set(['finish', 'traguardo', 'arrivo', 'end', 'fine']);

  /**
   * Elementi discendenti per nome: "trkpt" per nome locale (qualunque
   * prefisso), "gpstel:gate" per prefisso o per namespace GPSTelemetry.
   */
  function byTag(el, tag) {
    const local = tag.split(':').pop();
    const prefixed = tag.includes(':');
    return Array.from(el.getElementsByTagName('*')).filter((e) =>
      prefixed ? e.nodeName === tag || (e.namespaceURI === GPSTEL_NS && e.localName === local) : e.localName === local);
  }
  const child = (el, tag) => el && Array.from(el.children).find((c) => c.localName === tag);
  const text = (el, tag) => {
    const c = child(el, tag);
    return c ? c.textContent.trim() : null;
  };

  function points(els) {
    const out = [];
    for (const e of els) {
      const lat = parseFloat(e.getAttribute('lat'));
      const lon = parseFloat(e.getAttribute('lon'));
      if (!isFinite(lat) || !isFinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) continue;
      const ele = parseFloat(text(e, 'ele'));
      out.push(isFinite(ele) ? { lat, lon, ele } : { lat, lon });
    }
    return out;
  }

  /**
   * Converte un GPX (testo) in tracciato. Stesse regole dell'app:
   * estensioni gpstel se presenti, altrimenti deduzione da waypoint e percorso.
   * Lancia Error con un messaggio leggibile se il file non è utilizzabile.
   */
  function fromGpx(xml, parser = new root.DOMParser()) {
    if (/<!DOCTYPE/i.test(xml)) throw new Error('Il file contiene una DTD: non è supportato.');
    const doc = parser.parseFromString(xml, 'application/xml');
    const gpx = doc.documentElement;
    if (!gpx || gpx.localName !== 'gpx' || doc.getElementsByTagName('parsererror').length) {
      throw new Error('Il file non è un GPX valido.');
    }
    let path = points(byTag(gpx, 'trkpt'));
    if (path.length < 2) path = points(byTag(gpx, 'rtept'));

    const meta = byTag(gpx, 'gpstel:track')[0];
    const tolAttr = meta && parseFloat(meta.getAttribute('tolerance'));
    const tolerance = isFinite(tolAttr) ? Math.min(100, Math.max(5, tolAttr)) : DEFAULT_TOLERANCE;
    const metaType = meta && ['CIRCULAR', 'OPEN'].includes(meta.getAttribute('type')) ? meta.getAttribute('type') : null;

    let start = null;
    let finish = null;
    const splits = [];
    for (const w of byTag(gpx, 'wpt')) {
      const lat = parseFloat(w.getAttribute('lat'));
      const lon = parseFloat(w.getAttribute('lon'));
      if (!isFinite(lat) || !isFinite(lon)) continue;
      const name = text(w, 'name') || '';
      const ext = byTag(w, 'gpstel:gate')[0];
      const h = ext && parseFloat(ext.getAttribute('heading'));
      const wd = ext && parseFloat(ext.getAttribute('width'));
      const base = defaultGate({ lat, lon }, path, tolerance);
      const gate = {
        lat, lon,
        heading: isFinite(h) ? h : base.heading,
        width: Math.min(500, Math.max(4, isFinite(wd) ? wd : base.width)),
      };
      const role = (ext && ext.getAttribute('role')) ||
        (START_NAMES.has(name.toLowerCase()) ? 'start' : FINISH_NAMES.has(name.toLowerCase()) ? 'finish' : 'split');
      if (role === 'start' && !start) start = gate;
      else if (role === 'finish') finish = gate;
      else splits.push({ name: name || `Parziale ${splits.length + 1}`, gate });
    }

    if (!start && path.length < 2) throw new Error('Nessun percorso o punto di partenza nel file.');

    const first = path[0];
    const last = path[path.length - 1];
    const loop = path.length > 2 && haversine(first, last) <= LOOP_DISTANCE_M;
    const type = metaType || (finish ? 'OPEN' : loop ? 'CIRCULAR' : path.length >= 2 ? 'OPEN' : 'CIRCULAR');
    if (!start) start = defaultGate(first, path, tolerance);
    if (type === 'OPEN' && !finish && last) finish = defaultGate(last, path, tolerance);

    const name = text(child(gpx, 'metadata'), 'name') || text(child(gpx, 'trk'), 'name') || text(child(gpx, 'rte'), 'name');
    return {
      name: (name || 'Tracciato importato').slice(0, 60),
      type,
      tolerance,
      start,
      finish: type === 'OPEN' ? finish : null,
      splits,
      path,
    };
  }

  /** Nome di file sicuro (come MainActivity.safeName nell'app). */
  function safeName(s) {
    const n = String(s).replace(/[^\w \-À-ſ]+/g, '').trim().replace(/\s+/g, '_');
    return n || 'tracciato';
  }

  root.TrackCore = {
    GPSTEL_NS, DEFAULT_TOLERANCE, LOOP_DISTANCE_M,
    haversine, toXY, offset, bearing, normalize, gateEndpoints, distToSegment,
    pathHeadingAt, nearestSegment, pathLength, defaultGate,
    emptyTrack, validate, toGpx, fromGpx, safeName,
  };
})(typeof window !== 'undefined' ? window : globalThis);
