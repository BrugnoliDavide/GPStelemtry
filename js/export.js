/*
 * export.js — Esportazione dei dati.
 *
 * Formati prodotti:
 *  - GPX 1.1 (formato generico, importabile in Insta360 Studio per la
 *    sovrimpressione dei dati GPS): traccia con lat/lon, quota, orario e
 *    velocità/rotta nelle estensioni Garmin TrackPointExtension v2;
 *  - CSV della telemetria di moto (accelerometro e inclinazione);
 *  - JSON completo della sessione (backup integrale).
 */
'use strict';

const Exporter = (() => {
  const xmlEscape = (s) =>
    String(s)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');

  const iso = (ms) => new Date(ms).toISOString();

  function gpxHeader(name) {
    return (
      '<?xml version="1.0" encoding="UTF-8"?>\n' +
      '<gpx version="1.1" creator="GPSTelemetry"\n' +
      '  xmlns="http://www.topografix.com/GPX/1/1"\n' +
      '  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"\n' +
      '  xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v2"\n' +
      '  xsi:schemaLocation="http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd">\n' +
      `  <metadata><name>${xmlEscape(name)}</name><time>${iso(Date.now())}</time></metadata>\n`
    );
  }

  function trkpt(p, indent) {
    let s = `${indent}<trkpt lat="${p.lat.toFixed(7)}" lon="${p.lon.toFixed(7)}">`;
    if (p.ele != null) s += `<ele>${p.ele}</ele>`;
    if (p.t != null) s += `<time>${iso(p.t)}</time>`;
    const ext = [];
    if (p.speed != null) ext.push(`<gpxtpx:speed>${p.speed}</gpxtpx:speed>`);
    if (p.heading != null) ext.push(`<gpxtpx:course>${p.heading}</gpxtpx:course>`);
    if (ext.length) {
      s += `<extensions><gpxtpx:TrackPointExtension>${ext.join('')}</gpxtpx:TrackPointExtension></extensions>`;
    }
    return s + '</trkpt>\n';
  }

  /** GPX di una sessione cronometrata (telemetria GPS completa). */
  function sessionToGPX(session) {
    let out = gpxHeader(session.name || 'Sessione');
    // Waypoint di partenza/traguardo/parziali per riferimento visivo.
    if (session.trackRef) {
      const tr = session.trackRef;
      out += wpt(tr.start, 'Partenza');
      if (tr.type === 'open' && tr.finish) out += wpt(tr.finish, 'Traguardo');
      (tr.splits || []).forEach((sp, i) => {
        out += wpt(sp, sp.name || `Parziale ${i + 1}`);
      });
    }
    out += `  <trk><name>${xmlEscape(session.name || 'Sessione')}</name><trkseg>\n`;
    for (const p of session.samples || []) out += trkpt(p, '    ');
    out += '  </trkseg></trk>\n</gpx>\n';
    return out;
  }

  function wpt(p, name) {
    if (!p) return '';
    return `  <wpt lat="${p.lat.toFixed(7)}" lon="${p.lon.toFixed(7)}"><name>${xmlEscape(name)}</name></wpt>\n`;
  }

  /** GPX del solo tracciato definito (percorso + cancelli). */
  function trackToGPX(track) {
    let out = gpxHeader(track.name || 'Tracciato');
    out += wpt(track.start, 'Partenza');
    if (track.type === 'open' && track.finish) out += wpt(track.finish, 'Traguardo');
    (track.splits || []).forEach((sp, i) => {
      out += wpt(sp, sp.name || `Parziale ${i + 1}`);
    });
    if (track.path && track.path.length) {
      out += `  <trk><name>${xmlEscape(track.name || 'Tracciato')}</name><trkseg>\n`;
      for (const p of track.path) out += trkpt(p, '    ');
      out += '  </trkseg></trk>\n';
    }
    return out + '</gpx>\n';
  }

  /** CSV della telemetria di moto (accelerometro + inclinazione). */
  function motionToCSV(session) {
    const rows = ['time_iso,ax_ms2,ay_ms2,az_ms2,pitch_deg,roll_deg'];
    for (const m of session.motion || []) {
      rows.push(
        [iso(m.t), m.ax, m.ay, m.az, m.pitch != null ? m.pitch : '', m.roll != null ? m.roll : ''].join(',')
      );
    }
    return rows.join('\n') + '\n';
  }

  /** CSV dei campioni GPS (posizione, quota, velocità, precisione). */
  function gpsToCSV(session) {
    const rows = ['time_iso,lat,lon,ele_m,speed_ms,heading_deg,accuracy_m'];
    for (const p of session.samples || []) {
      rows.push(
        [
          iso(p.t), p.lat, p.lon,
          p.ele != null ? p.ele : '',
          p.speed != null ? p.speed : '',
          p.heading != null ? p.heading : '',
          p.acc != null ? p.acc : '',
        ].join(',')
      );
    }
    return rows.join('\n') + '\n';
  }

  /** Avvia il download di un file generato lato client. */
  function download(filename, content, mime) {
    const blob = new Blob([content], { type: mime || 'application/octet-stream' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 5000);
  }

  const safeName = (s) =>
    String(s || 'export').replace(/[^\wÀ-ſ -]+/g, '').trim().replace(/\s+/g, '_') || 'export';

  return { sessionToGPX, trackToGPX, motionToCSV, gpsToCSV, download, safeName };
})();
