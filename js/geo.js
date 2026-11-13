/*
 * geo.js — Funzioni geodetiche e geometriche.
 * Tutte le distanze sono espresse in metri, i tempi in millisecondi.
 */
'use strict';

const Geo = (() => {
  const R = 6371008.8; // raggio medio terrestre (m)
  const rad = (deg) => (deg * Math.PI) / 180;

  /** Distanza haversine tra due punti {lat, lon} in metri. */
  function haversine(a, b) {
    const dLat = rad(b.lat - a.lat);
    const dLon = rad(b.lon - a.lon);
    const s =
      Math.sin(dLat / 2) ** 2 +
      Math.cos(rad(a.lat)) * Math.cos(rad(b.lat)) * Math.sin(dLon / 2) ** 2;
    return 2 * R * Math.asin(Math.min(1, Math.sqrt(s)));
  }

  /**
   * Proiezione equirettangolare locale attorno a un punto di riferimento.
   * Valida per le distanze in gioco (tracciati di pochi km).
   * Restituisce {x, y} in metri.
   */
  function toXY(p, ref) {
    const x = rad(p.lon - ref.lon) * R * Math.cos(rad(ref.lat));
    const y = rad(p.lat - ref.lat) * R;
    return { x, y };
  }

  /** Distanza punto-segmento in metri (p, a, b: {lat, lon}). */
  function distToSegment(p, a, b) {
    const P = toXY(p, a);
    const B = toXY(b, a);
    const len2 = B.x * B.x + B.y * B.y;
    if (len2 === 0) return Math.hypot(P.x, P.y);
    let t = (P.x * B.x + P.y * B.y) / len2;
    t = Math.max(0, Math.min(1, t));
    return Math.hypot(P.x - t * B.x, P.y - t * B.y);
  }

  /**
   * Distanza minima dal punto p alla polilinea path (array di {lat, lon}).
   * Usa un pre-filtro grossolano sui vertici per limitare i calcoli.
   */
  function distToPath(p, path) {
    if (!path || path.length === 0) return Infinity;
    if (path.length === 1) return haversine(p, path[0]);
    let best = Infinity;
    for (let i = 0; i < path.length - 1; i++) {
      // pre-filtro: se entrambi i vertici distano molto più del best, salta
      const dv = haversine(p, path[i]);
      const segLen = haversine(path[i], path[i + 1]);
      if (dv - segLen > best) continue;
      const d = distToSegment(p, path[i], path[i + 1]);
      if (d < best) best = d;
    }
    return best;
  }

  /** Lunghezza totale di una polilinea in metri. */
  function pathLength(path) {
    let L = 0;
    for (let i = 1; i < path.length; i++) L += haversine(path[i - 1], path[i]);
    return L;
  }

  /**
   * Interpolazione dell'istante di attraversamento di una soglia di distanza.
   * prev e cur: {lat, lon, t} campioni GPS consecutivi; gate: {lat, lon};
   * radius: soglia in metri. Presuppone dPrev > radius >= dCur (ingresso).
   * Restituisce il timestamp interpolato linearmente.
   */
  function interpolateCrossing(prev, cur, gate, radius) {
    const dPrev = haversine(prev, gate);
    const dCur = haversine(cur, gate);
    if (dPrev === dCur) return cur.t;
    const f = Math.max(0, Math.min(1, (dPrev - radius) / (dPrev - dCur)));
    return prev.t + f * (cur.t - prev.t);
  }

  /**
   * Intersezione tra il segmento prev→cur e la zona circolare di un cancello.
   * Necessaria perché, a velocità elevate, due fix GPS consecutivi possono
   * "scavalcare" il cancello senza che alcun campione cada dentro il raggio.
   * prev, cur: {lat, lon, t}; gate: {lat, lon}; radius in metri.
   * Restituisce null se il segmento non entra nella zona, altrimenti:
   *   { tEntry, tExit, minDist, startInside, endInside }
   * con tEntry/tExit timestamp interpolati di ingresso/uscita dalla zona.
   */
  function segmentGate(prev, cur, gate, radius) {
    const P0 = toXY(prev, gate);
    const P1 = toXY(cur, gate);
    const dx = P1.x - P0.x;
    const dy = P1.y - P0.y;
    const a = dx * dx + dy * dy;
    const b = 2 * (P0.x * dx + P0.y * dy);
    const c = P0.x * P0.x + P0.y * P0.y - radius * radius;
    const distAt = (t) => Math.hypot(P0.x + t * dx, P0.y + t * dy);
    const tMin = a > 0 ? Math.max(0, Math.min(1, -b / (2 * a))) : 0;
    if (distAt(tMin) > radius) return null;
    let tEntry = 0;
    let tExit = 1;
    const disc = b * b - 4 * a * c;
    if (a > 0 && disc >= 0) {
      const sq = Math.sqrt(disc);
      tEntry = Math.max(0, Math.min(1, (-b - sq) / (2 * a)));
      tExit = Math.max(0, Math.min(1, (-b + sq) / (2 * a)));
    }
    return {
      tEntry: prev.t + tEntry * (cur.t - prev.t),
      tExit: prev.t + tExit * (cur.t - prev.t),
      minDist: distAt(tMin),
      startInside: c <= 0,
      endInside: distAt(1) <= radius,
    };
  }

  /** Semplificazione di Douglas-Peucker (toleranza in metri). */
  function simplify(path, tolerance) {
    if (!path || path.length <= 2) return path ? path.slice() : [];
    const keep = new Array(path.length).fill(false);
    keep[0] = keep[path.length - 1] = true;
    const stack = [[0, path.length - 1]];
    while (stack.length) {
      const [i0, i1] = stack.pop();
      let maxD = -1;
      let idx = -1;
      for (let i = i0 + 1; i < i1; i++) {
        const d = distToSegment(path[i], path[i0], path[i1]);
        if (d > maxD) {
          maxD = d;
          idx = i;
        }
      }
      if (maxD > tolerance && idx > 0) {
        keep[idx] = true;
        stack.push([i0, idx], [idx, i1]);
      }
    }
    return path.filter((_, i) => keep[i]);
  }

  return { haversine, toXY, distToSegment, distToPath, pathLength, interpolateCrossing, segmentGate, simplify };
})();
