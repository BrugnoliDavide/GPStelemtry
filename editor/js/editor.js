/*
 * editor.js — Editor web dei tracciati di GPS Telemetry (PC e tablet).
 *
 * - Mappa satellitare (Esri World Imagery) o stradale (OpenStreetMap).
 * - Percorso: strumento "Percorso", clic per aggiungere punti, punti
 *   trascinabili, clic sul percorso per inserirne uno, clic destro per
 *   eliminarlo.
 * - Linee di cronometraggio (partenza, traguardo, parziali): clic per
 *   posizionarle (perpendicolari al percorso), pallino per spostarle,
 *   maniglia per ruotarle, cursori di angolo e larghezza nel pannello.
 * - Importa/esporta GPX nello stesso formato dell'app (TrackCore).
 * - Bozza salvata automaticamente nel browser; annulla con Ctrl+Z.
 */
(function () {
  'use strict';

  const TC = window.TrackCore;
  const $ = (id) => document.getElementById(id);
  const DRAFT_KEY = 'gpstel-editor-draft';
  const PREFS_KEY = 'gpstel-editor-prefs';
  const COLORS = { start: '#00E676', finish: '#FF2D2D', split: '#FF6A00', path: '#FF6A00' };

  /* --------------------------------- Stato ------------------------------- */

  let track = TC.emptyTrack();
  let tool = null; // null | 'path' | 'start' | 'finish' | 'split'
  let selected = null; // 'start' | 'finish' | 'split:N'
  const history = [];
  let dragging = false;

  const prefs = (() => {
    try { return JSON.parse(localStorage.getItem(PREFS_KEY)) || {}; } catch (e) { return {}; }
  })();
  const savePrefs = () => { try { localStorage.setItem(PREFS_KEY, JSON.stringify(prefs)); } catch (e) { /* ignora */ } };

  /* --------------------------------- Mappa ------------------------------- */

  const map = L.map('map', { zoomControl: false, doubleClickZoom: false, worldCopyJump: true })
    .setView([45.4642, 9.19], 13);
  L.control.zoom({ position: 'bottomright' }).addTo(map);
  L.control.scale({ position: 'bottomright', imperial: false }).addTo(map);

  const layers = {
    satellite: L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}', {
      maxZoom: 21, maxNativeZoom: 19,
      attribution: 'Immagini &copy; Esri, Maxar, Earthstar Geographics e la comunità GIS',
    }),
    map: L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
      maxZoom: 21, maxNativeZoom: 19,
      attribution: '&copy; contributori di <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>',
    }),
  };
  let layerName = prefs.layer === 'map' ? 'map' : 'satellite';
  layers[layerName].addTo(map);

  const ICON_MAP = '<svg viewBox="0 0 24 24"><path d="M20.5,3l-0.16,0.03L15,5.1 9,3 3.36,4.9c-0.21,0.07 -0.36,0.25 -0.36,0.48V20.5c0,0.28 0.22,0.5 0.5,0.5l0.16,-0.03L9,18.9l6,2.1 5.64,-1.9c0.21,-0.07 0.36,-0.25 0.36,-0.48V3.5c0,-0.28 -0.22,-0.5 -0.5,-0.5zM15,19l-6,-2.11V5l6,2.11V19z"/></svg>';
  const ICON_SAT = '<svg viewBox="0 0 24 24"><path d="M19,3H5C3.9,3 3,3.9 3,5v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2V5c0,-1.1 -0.9,-2 -2,-2zM5,4.99h3C8,6.65 6.66,8 5,8V4.99zM5,12v-2c2.76,0 5,-2.25 5,-5.01h2C12,8.86 8.87,12 5,12zM5,18l3.5,-4.5 2.5,3.01L14.5,12l4.5,6H5z"/></svg>';

  function setLayer(name) {
    map.removeLayer(layers[layerName]);
    layerName = name;
    layers[layerName].addTo(map);
    $('btnLayer').innerHTML = layerName === 'satellite' ? ICON_MAP : ICON_SAT;
    $('btnLayer').title = layerName === 'satellite' ? 'Passa alla mappa stradale' : 'Passa alla vista satellitare';
    document.body.classList.toggle('street', layerName === 'map');
    prefs.layer = layerName;
    savePrefs();
  }

  const pathGroup = L.layerGroup().addTo(map);
  const vertexGroup = L.layerGroup().addTo(map);
  const gateLineGroup = L.layerGroup().addTo(map);
  const gateMarkerGroup = L.layerGroup().addTo(map);

  /* ------------------------------ Utilità ------------------------------- */

  const ll = (p) => L.latLng(p.lat, p.lon);
  const fromLL = (l) => ({ lat: l.lat, lon: l.lng });
  const fmtDist = (m) => (m >= 1000 ? `${(m / 1000).toFixed(2)} km` : `${Math.round(m)} m`);
  const clone = (o) => JSON.parse(JSON.stringify(o));

  function gateOf(key) {
    if (key === 'start') return track.start;
    if (key === 'finish') return track.finish;
    if (key && key.startsWith('split:')) return (track.splits[+key.slice(6)] || {}).gate || null;
    return null;
  }

  function setGate(key, g) {
    if (key === 'start') track.start = g;
    else if (key === 'finish') track.finish = g;
    else if (key.startsWith('split:')) track.splits[+key.slice(6)].gate = g;
  }

  function gateItems() {
    const items = [];
    if (track.start) {
      items.push({ key: 'start', label: track.type === 'CIRCULAR' ? 'START · ARRIVO' : 'PARTENZA', color: COLORS.start, checkered: true, gate: track.start });
    }
    if (track.type === 'OPEN' && track.finish) {
      items.push({ key: 'finish', label: 'ARRIVO', color: COLORS.finish, checkered: true, gate: track.finish });
    }
    track.splits.forEach((s, i) => items.push({ key: `split:${i}`, label: `P${i + 1}`, color: COLORS.split, checkered: false, gate: s.gate }));
    return items;
  }

  function toast(msg, ms = 2600) {
    const t = $('toast');
    t.textContent = msg;
    t.hidden = false;
    clearTimeout(toast.timer);
    toast.timer = setTimeout(() => { t.hidden = true; }, ms);
  }

  /* ----------------------- Cronologia e salvataggio --------------------- */

  /** Registra lo stato prima di una modifica (per Annulla). */
  function snapshot() {
    history.push(JSON.stringify(track));
    if (history.length > 100) history.shift();
  }

  function undo() {
    const prev = history.pop();
    if (!prev) return toast('Niente da annullare');
    track = JSON.parse(prev);
    if (selected && !gateOf(selected)) selected = null;
    renderAll();
    saveDraft();
  }

  function saveDraft() {
    clearTimeout(saveDraft.timer);
    saveDraft.timer = setTimeout(() => {
      try {
        localStorage.setItem(DRAFT_KEY, JSON.stringify({ track, savedAt: Date.now() }));
        $('saved').textContent = 'Bozza salvata nel browser';
      } catch (e) {
        $('saved').textContent = '';
      }
    }, 300);
  }

  function isEmpty(t) {
    return !t.name && !t.start && !t.finish && !t.splits.length && !t.path.length;
  }

  /* ------------------------------ Disegno ------------------------------- */

  function renderAll() {
    renderPath();
    renderGates();
    renderPanel();
  }

  function renderPath() {
    pathGroup.clearLayers();
    vertexGroup.clearLayers();
    const pts = track.path.map(ll);
    if (pts.length > 1) {
      L.polyline(pts, { color: '#000', weight: 9, opacity: 0.55, interactive: false }).addTo(pathGroup);
      const line = L.polyline(pts, { color: COLORS.path, weight: 5, opacity: 0.95, className: 'path-line' }).addTo(pathGroup);
      // Strumento percorso: clic sulla linea inserisce un punto nel tratto.
      line.on('click', (e) => {
        if (tool !== 'path') return;
        L.DomEvent.stop(e);
        const p = fromLL(e.latlng);
        const seg = TC.nearestSegment(track.path, p);
        snapshot();
        track.path.splice(seg.index + 1, 0, p);
        renderPath();
        renderPanel();
        saveDraft();
      });
    }
    if (pts.length) {
      // Inizio e fine del percorso.
      L.circleMarker(pts[0], { radius: 6, color: '#000', weight: 2, fillColor: '#fff', fillOpacity: 1, interactive: false }).addTo(pathGroup);
      if (pts.length > 1) {
        L.circleMarker(pts[pts.length - 1], { radius: 6, color: '#000', weight: 2, fillColor: COLORS.path, fillOpacity: 1, interactive: false }).addTo(pathGroup);
      }
    }
    if (tool !== 'path') return;
    // Punti modificabili (solo con lo strumento percorso).
    track.path.forEach((p, i) => {
      const m = L.marker(ll(p), {
        draggable: true,
        icon: L.divIcon({ className: 'vertex', iconSize: [14, 14] }),
        title: 'Trascina per spostare · clic destro per eliminare',
      }).addTo(vertexGroup);
      m.on('dragstart', () => { snapshot(); dragging = true; });
      m.on('drag', (e) => {
        track.path[i] = { ...track.path[i], ...fromLL(e.latlng) };
        const lines = pathGroup.getLayers().filter((l) => l instanceof L.Polyline && !(l instanceof L.CircleMarker));
        lines.forEach((l) => l.setLatLngs(track.path.map(ll)));
      });
      m.on('dragend', () => { dragging = false; renderPath(); renderPanel(); saveDraft(); });
      m.on('contextmenu', (e) => {
        L.DomEvent.stop(e);
        snapshot();
        track.path.splice(i, 1);
        renderPath();
        renderPanel();
        saveDraft();
      });
    });
  }

  /**
   * Geometria della linea a schermo: estremi (con lunghezza minima visibile),
   * verso dell'asse rivolto all'interno del circuito, posizione della
   * maniglia (lato interno) e dell'etichetta (lato esterno).
   */
  function screenGeometry(g, centroid) {
    const c = map.latLngToContainerPoint(ll(g));
    const [e1, e2] = TC.gateEndpoints(g).map((p) => map.latLngToContainerPoint(ll(p)));
    let dx = e2.x - e1.x;
    let dy = e2.y - e1.y;
    let len = Math.hypot(dx, dy);
    if (len < 0.5) {
      const r = (g.heading * Math.PI) / 180;
      dx = Math.sin(r);
      dy = -Math.cos(r);
      len = 1;
    }
    let ux = dx / len;
    let uy = dy / len;
    if (centroid && (centroid.x - c.x) * ux + (centroid.y - c.y) * uy < 0) {
      ux = -ux;
      uy = -uy;
    }
    const half = Math.max(len / 2, 26);
    const a = L.point(c.x - ux * half, c.y - uy * half);
    const b = L.point(c.x + ux * half, c.y + uy * half);
    const handle = L.point(c.x + ux * (half + 30), c.y + uy * (half + 30));
    const label = L.point(c.x - ux * (half + 22), c.y - uy * (half + 22));
    const toLL = (p) => map.containerPointToLatLng(p);
    return { a: toLL(a), b: toLL(b), handle: toLL(handle), label: toLL(label) };
  }

  function centroidPx(items) {
    if (!items.length) return null;
    let x = 0;
    let y = 0;
    items.forEach((it) => {
      const p = map.latLngToContainerPoint(ll(it.gate));
      x += p.x;
      y += p.y;
    });
    return L.point(x / items.length, y / items.length);
  }

  /** Linee ed etichette (ridisegnabili durante il trascinamento). */
  function renderGateLines() {
    gateLineGroup.clearLayers();
    const items = gateItems();
    const cen = centroidPx(items);
    items.forEach((it) => {
      const sel = it.key === selected;
      const geo = screenGeometry(it.gate, cen);
      const w = sel ? 8 : 6;
      L.polyline([geo.a, geo.b], { color: '#000', weight: w + 5, opacity: 0.85, lineCap: 'round', interactive: false }).addTo(gateLineGroup);
      if (it.checkered) {
        L.polyline([geo.a, geo.b], { color: '#fff', weight: w, opacity: 1, lineCap: 'butt', interactive: false }).addTo(gateLineGroup);
        L.polyline([geo.a, geo.b], { color: '#000', weight: w, opacity: 1, dashArray: `${w} ${w}`, lineCap: 'butt', interactive: false }).addTo(gateLineGroup);
      } else {
        L.polyline([geo.a, geo.b], { color: it.color, weight: w, opacity: 1, lineCap: 'butt', interactive: false }).addTo(gateLineGroup);
      }
      if (sel) {
        L.polyline([ll(it.gate), geo.handle], { color: '#fff', weight: 1.5, dashArray: '4 4', opacity: 0.8, interactive: false }).addTo(gateLineGroup);
      }
      L.marker(geo.label, {
        interactive: false,
        keyboard: false,
        icon: L.divIcon({
          className: 'gate-label',
          html: `<span style="--c:${it.color}">${it.label}</span>`,
          iconSize: null,
        }),
      }).addTo(gateLineGroup);
    });
  }

  function renderGates() {
    renderGateLines();
    gateMarkerGroup.clearLayers();
    const items = gateItems();
    const cen = centroidPx(items);
    items.forEach((it) => {
      const sel = it.key === selected;
      // Pallino centrale: sposta la linea.
      const center = L.marker(ll(it.gate), {
        draggable: true,
        zIndexOffset: sel ? 1000 : 500,
        icon: L.divIcon({ className: `gate-dot${sel ? ' selected' : ''}`, html: `<span style="--c:${it.color}"></span>`, iconSize: [26, 26] }),
        title: 'Trascina per spostare · clic per selezionare',
      }).addTo(gateMarkerGroup);
      center.on('click', () => select(it.key === selected ? null : it.key));
      center.on('dragstart', () => { snapshot(); dragging = true; });
      center.on('drag', (e) => {
        setGate(it.key, { ...gateOf(it.key), ...fromLL(e.latlng) });
        renderGateLines();
      });
      center.on('dragend', () => { dragging = false; select(it.key); saveDraft(); });

      if (!sel) return;
      // Maniglia: ruota la linea.
      const geo = screenGeometry(it.gate, cen);
      const handle = L.marker(geo.handle, {
        draggable: true,
        zIndexOffset: 1100,
        icon: L.divIcon({
          className: 'gate-handle',
          html: `<span style="--c:${it.color}"><svg viewBox="0 0 24 24"><path d="M15.55,5.55L11,1v3.07C7.06,4.56 4,7.92 4,12s3.05,7.44 7,7.93v-2.02c-2.84,-0.48 -5,-2.94 -5,-5.91s2.16,-5.43 5,-5.91V10l4.55,-4.45zM19.93,11c-0.17,-1.39 -0.72,-2.73 -1.62,-3.89l-1.42,1.42c0.54,0.75 0.88,1.6 1.02,2.47h2.02zM13,17.9v2.02c1.39,-0.17 2.74,-0.71 3.9,-1.61l-1.44,-1.44c-0.75,0.54 -1.59,0.89 -2.46,1.03zM16.89,15.48l1.42,1.41c0.9,-1.16 1.45,-2.5 1.62,-3.89h-2.02c-0.14,0.87 -0.48,1.72 -1.02,2.48z"/></svg></span>`,
          iconSize: [34, 34],
        }),
        title: 'Trascina per ruotare la linea',
      }).addTo(gateMarkerGroup);
      handle.on('dragstart', () => { snapshot(); dragging = true; });
      handle.on('drag', (e) => {
        const g = gateOf(it.key);
        setGate(it.key, { ...g, heading: TC.normalize(TC.bearing(g, fromLL(e.latlng))) });
        renderGateLines();
        updateGateCard(it.key);
      });
      handle.on('dragend', () => { dragging = false; renderGates(); saveDraft(); });
    });
  }

  /* ------------------------------ Pannello ------------------------------ */

  function renderPanel() {
    $('trackName').value = track.name;
    document.querySelectorAll('.seg').forEach((b) => b.classList.toggle('on', b.dataset.type === track.type));
    $('tolerance').value = track.tolerance;
    $('toleranceValue').textContent = `± ${Math.round(track.tolerance)} m`;
    document.querySelectorAll('.tool').forEach((b) => b.classList.toggle('on', b.dataset.tool === tool));
    document.querySelector('.tool[data-tool="finish"]').classList.toggle('disabled', track.type === 'CIRCULAR');
    $('hint').textContent = hintText();
    $('statLength').textContent = track.path.length > 1 ? fmtDist(TC.pathLength(track.path)) : '—';
    $('statSectors').textContent = track.start ? String(track.splits.length + 1) : '—';
    $('statPoints').textContent = String(track.path.length);
    $('btnUndo').disabled = !track.path.length;
    $('btnReverse').disabled = track.path.length < 2;
    $('btnClearPath').disabled = !track.path.length;
    renderGateList();
    $('errors').textContent = '';
    document.getElementById('map').classList.toggle('picking', !!tool);
  }

  function hintText() {
    switch (tool) {
      case 'path': return 'Clic sulla mappa per aggiungere punti alla fine del percorso; clic sul percorso per inserirne uno; trascina i punti; clic destro per eliminarli.';
      case 'start': return 'Clic sulla mappa per posizionare la linea di partenza.';
      case 'finish': return 'Clic sulla mappa per posizionare la linea del traguardo.';
      case 'split': return 'Clic sulla mappa per aggiungere i parziali nell\'ordine di percorrenza.';
      default: return 'Scegli uno strumento. Le linee si spostano trascinando il pallino e si ruotano con la maniglia della linea selezionata.';
    }
  }

  function gateTitle(key) {
    if (key === 'start') return track.type === 'CIRCULAR' ? 'Partenza / Arrivo' : 'Partenza';
    if (key === 'finish') return 'Traguardo';
    const i = +key.slice(6);
    return `P${i + 1}`;
  }

  function renderGateList() {
    const list = $('gateList');
    list.innerHTML = '';
    const items = gateItems();
    if (!items.length) {
      list.innerHTML = '<p class="hint">Nessuna linea: usa gli strumenti Partenza, Traguardo e Parziale.</p>';
      return;
    }
    items.forEach((it) => {
      const card = document.createElement('div');
      card.className = `gate-card${it.key === selected ? ' selected' : ''}`;
      card.dataset.key = it.key;
      const isSplit = it.key.startsWith('split:');
      const idx = isSplit ? +it.key.slice(6) : -1;
      card.innerHTML = `
        <div class="gate-head">
          <span class="swatch${it.checkered ? ' checkered' : ''}" style="--c:${it.color}"></span>
          <strong>${gateTitle(it.key)}</strong>
          ${isSplit ? `<input class="field small split-name" value="" maxlength="40" aria-label="Nome del parziale">` : '<span class="grow"></span>'}
          ${isSplit ? `<button class="mini" data-act="up" title="Sposta prima" ${idx === 0 ? 'disabled' : ''}>▲</button>
          <button class="mini" data-act="down" title="Sposta dopo" ${idx === track.splits.length - 1 ? 'disabled' : ''}>▼</button>` : ''}
          <button class="mini danger-text" data-act="del" title="Elimina la linea">✕</button>
        </div>
        <div class="gate-controls">
          <label>Angolo <span class="value" data-v="heading"></span></label>
          <input type="range" min="0" max="179" step="1" data-k="heading">
          <label>Larghezza <span class="value" data-v="width"></span></label>
          <input type="range" min="4" max="200" step="1" data-k="width">
          <button class="link" data-act="perp" title="Perpendicolare al percorso">⟂ Perpendicolare al percorso</button>
        </div>`;
      if (isSplit) card.querySelector('.split-name').value = track.splits[idx].name;
      list.appendChild(card);
      updateGateCard(it.key);

      card.addEventListener('click', (e) => {
        if (e.target.closest('input, button')) return;
        select(it.key, true);
      });
      card.querySelectorAll('input[type=range]').forEach((r) => {
        r.addEventListener('pointerdown', () => snapshot());
        r.addEventListener('keydown', () => snapshot(), { once: true });
        r.addEventListener('input', () => {
          const g = gateOf(it.key);
          setGate(it.key, { ...g, [r.dataset.k]: +r.value });
          if (selected !== it.key) select(it.key);
          renderGateLines();
          updateGateCard(it.key);
        });
        r.addEventListener('change', () => { renderGates(); saveDraft(); });
      });
      const nameInput = card.querySelector('.split-name');
      if (nameInput) {
        nameInput.addEventListener('focus', () => snapshot());
        nameInput.addEventListener('input', () => { track.splits[idx].name = nameInput.value; saveDraft(); });
      }
      card.querySelectorAll('[data-act]').forEach((b) => b.addEventListener('click', () => gateAction(it.key, b.dataset.act)));
    });
  }

  function updateGateCard(key) {
    const card = document.querySelector(`.gate-card[data-key="${key}"]`);
    const g = gateOf(key);
    if (!card || !g) return;
    const h = Math.round(TC.normalize(g.heading)) % 180;
    const w = Math.round(g.width);
    const hr = card.querySelector('[data-k=heading]');
    const wr = card.querySelector('[data-k=width]');
    if (document.activeElement !== hr) hr.value = h;
    wr.max = Math.max(200, w);
    if (document.activeElement !== wr) wr.value = w;
    card.querySelector('[data-v=heading]').textContent = `${h}°`;
    card.querySelector('[data-v=width]').textContent = `${w} m`;
  }

  function gateAction(key, act) {
    snapshot();
    const isSplit = key.startsWith('split:');
    const i = isSplit ? +key.slice(6) : -1;
    if (act === 'del') {
      if (key === 'start') track.start = null;
      else if (key === 'finish') track.finish = null;
      else track.splits.splice(i, 1);
      selected = null;
    } else if (act === 'up' && i > 0) {
      [track.splits[i - 1], track.splits[i]] = [track.splits[i], track.splits[i - 1]];
      selected = `split:${i - 1}`;
    } else if (act === 'down' && i < track.splits.length - 1) {
      [track.splits[i + 1], track.splits[i]] = [track.splits[i], track.splits[i + 1]];
      selected = `split:${i + 1}`;
    } else if (act === 'perp') {
      const g = gateOf(key);
      const h = TC.pathHeadingAt(track.path, g);
      if (h == null) {
        history.pop();
        return toast('Disegna prima il percorso per orientare la linea automaticamente.');
      }
      setGate(key, { ...g, heading: TC.normalize(h + 90) });
    }
    renderAll();
    saveDraft();
  }

  function select(key, focusMap) {
    selected = key;
    renderGates();
    document.querySelectorAll('.gate-card').forEach((c) => c.classList.toggle('selected', c.dataset.key === key));
    if (key) {
      const card = document.querySelector(`.gate-card[data-key="${key}"]`);
      if (card) card.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
      const g = gateOf(key);
      if (focusMap && g) map.flyTo(ll(g), Math.max(map.getZoom(), 18), { duration: 0.6 });
    }
  }

  function setTool(t) {
    if (t === 'finish' && track.type === 'CIRCULAR') {
      toast('Tracciato circolare: il traguardo coincide con la partenza. Passa ad "Aperto" per un traguardo separato.', 3500);
      return;
    }
    tool = tool === t ? null : t;
    renderPath();
    renderPanel();
  }

  /* -------------------------------- Eventi ------------------------------ */

  map.on('click', (e) => {
    const p = fromLL(e.latlng);
    switch (tool) {
      case 'path':
        snapshot();
        track.path.push(p);
        renderPath();
        renderPanel();
        break;
      case 'start':
      case 'finish': {
        snapshot();
        const g = TC.defaultGate(p, track.path, track.tolerance);
        if (tool === 'start') track.start = g; else track.finish = g;
        const key = tool;
        tool = null;
        selected = key;
        renderAll();
        if (map.getZoom() < 17) map.flyTo(e.latlng, 18, { duration: 0.6 });
        break;
      }
      case 'split':
        snapshot();
        track.splits.push({ name: `Parziale ${track.splits.length + 1}`, gate: TC.defaultGate(p, track.path, track.tolerance) });
        selected = `split:${track.splits.length - 1}`;
        renderAll();
        break;
      default:
        if (selected) select(null);
        return;
    }
    saveDraft();
  });

  map.on('zoomend', () => { if (!dragging) renderGates(); });

  $('trackName').addEventListener('focus', () => snapshot());
  $('trackName').addEventListener('input', (e) => { track.name = e.target.value; saveDraft(); });
  document.querySelectorAll('.seg').forEach((b) => b.addEventListener('click', () => {
    if (track.type === b.dataset.type) return;
    snapshot();
    track.type = b.dataset.type;
    if (track.type === 'CIRCULAR' && selected === 'finish') selected = null;
    if (tool === 'finish') tool = null;
    renderAll();
    saveDraft();
  }));
  $('tolerance').addEventListener('pointerdown', () => snapshot());
  $('tolerance').addEventListener('input', (e) => {
    track.tolerance = +e.target.value;
    $('toleranceValue').textContent = `± ${track.tolerance} m`;
    saveDraft();
  });
  document.querySelectorAll('.tool').forEach((b) => b.addEventListener('click', () => setTool(b.dataset.tool)));

  $('btnUndo').addEventListener('click', () => {
    if (!track.path.length) return;
    snapshot();
    track.path.pop();
    renderPath();
    renderPanel();
    saveDraft();
  });
  $('btnReverse').addEventListener('click', () => {
    snapshot();
    track.path.reverse();
    renderAll();
    saveDraft();
    toast('Senso del percorso invertito');
  });
  $('btnClearPath').addEventListener('click', () => {
    if (!confirm('Cancellare tutti i punti del percorso?')) return;
    snapshot();
    track.path = [];
    renderAll();
    saveDraft();
  });
  $('btnAllPerp').addEventListener('click', () => {
    if (track.path.length < 2) return toast('Disegna prima il percorso.');
    snapshot();
    gateItems().forEach((it) => {
      const h = TC.pathHeadingAt(track.path, it.gate);
      if (h != null) setGate(it.key, { ...it.gate, heading: TC.normalize(h + 90) });
    });
    renderAll();
    saveDraft();
  });

  $('btnNew').addEventListener('click', () => {
    if (!isEmpty(track) && !confirm('Iniziare un nuovo tracciato? Quello attuale verrà eliminato dalla bozza (puoi annullare con Ctrl+Z).')) return;
    snapshot();
    track = TC.emptyTrack();
    selected = null;
    tool = null;
    renderAll();
    saveDraft();
  });

  /* -------------------------- Import / export --------------------------- */

  function importText(text, fileName) {
    let t;
    try {
      t = TC.fromGpx(text);
    } catch (err) {
      return toast(`Impossibile importare ${fileName}: ${err.message}`, 4500);
    }
    if (!isEmpty(track) && !confirm(`Sostituire il tracciato attuale con "${t.name}"? (Puoi annullare con Ctrl+Z.)`)) return;
    snapshot();
    track = t;
    selected = null;
    tool = null;
    renderAll();
    fitTrack();
    saveDraft();
    toast(`"${t.name}" importato: controlla le linee prima di esportare.`, 3500);
  }

  function readFile(file) {
    if (!file) return;
    if (file.size > 20 * 1024 * 1024) return toast('File troppo grande (massimo 20 MB).');
    const r = new FileReader();
    r.onload = () => importText(String(r.result), file.name);
    r.onerror = () => toast('Impossibile leggere il file.');
    r.readAsText(file);
  }

  $('fileInput').addEventListener('change', (e) => {
    readFile(e.target.files[0]);
    e.target.value = '';
  });

  // Trascinamento del file sulla pagina.
  let dragDepth = 0;
  window.addEventListener('dragenter', (e) => {
    if (!e.dataTransfer || !Array.from(e.dataTransfer.types).includes('Files')) return;
    dragDepth++;
    $('dropHint').hidden = false;
  });
  window.addEventListener('dragleave', () => { if (--dragDepth <= 0) { dragDepth = 0; $('dropHint').hidden = true; } });
  window.addEventListener('dragover', (e) => e.preventDefault());
  window.addEventListener('drop', (e) => {
    e.preventDefault();
    dragDepth = 0;
    $('dropHint').hidden = true;
    readFile(e.dataTransfer && e.dataTransfer.files[0]);
  });

  $('btnExport').addEventListener('click', () => {
    const errors = TC.validate(track);
    if (errors.length) {
      $('errors').textContent = errors.join(' ');
      return;
    }
    const blob = new Blob([TC.toGpx(track)], { type: 'application/gpx+xml' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = `${TC.safeName(track.name)}.gpx`;
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(a.href), 1000);
    toast('GPX esportato: importalo nell\'app con il tasto in alto a destra della scheda Cronometra.', 4500);
  });

  /* ------------------------------ Navigazione --------------------------- */

  function fitTrack() {
    const pts = track.path.map(ll).concat(gateItems().map((it) => ll(it.gate)));
    if (!pts.length) return toast('Il tracciato è vuoto.');
    if (pts.length === 1) return map.flyTo(pts[0], 18);
    // Margini calcolati dal pannello reale (laterale su PC/tablet, in basso sui telefoni)
    // più lo spazio per le etichette delle linee.
    const panel = $('panel').getBoundingClientRect();
    const narrow = window.innerWidth < 760;
    map.flyToBounds(L.latLngBounds(pts), {
      paddingTopLeft: narrow ? [60, 90] : [panel.right + 60, 80],
      paddingBottomRight: narrow ? [110, window.innerHeight - panel.top + 40] : [130, 60],
      duration: 0.8,
    });
  }

  $('btnFit').addEventListener('click', fitTrack);
  $('btnLayer').addEventListener('click', () => setLayer(layerName === 'satellite' ? 'map' : 'satellite'));
  $('btnLocate').addEventListener('click', () => {
    if (!navigator.geolocation) return toast('Posizione non disponibile in questo browser.');
    navigator.geolocation.getCurrentPosition(
      (pos) => map.flyTo([pos.coords.latitude, pos.coords.longitude], 17),
      () => toast('Posizione non disponibile (permesso negato o assente).'),
      { enableHighAccuracy: true, timeout: 10000 },
    );
  });

  // Ricerca di un luogo (OpenStreetMap Nominatim, solo su richiesta).
  $('search').addEventListener('submit', async (e) => {
    e.preventDefault();
    const q = $('searchInput').value.trim();
    if (!q) return;
    try {
      const r = await fetch(`https://nominatim.openstreetmap.org/search?format=json&limit=1&q=${encodeURIComponent(q)}`, {
        headers: { 'Accept-Language': 'it' },
      });
      const res = await r.json();
      if (!res.length) return toast('Nessun risultato.');
      const b = res[0].boundingbox.map(Number);
      map.flyToBounds([[b[0], b[2]], [b[1], b[3]]], { maxZoom: 17, duration: 0.8 });
    } catch (err) {
      toast('Ricerca non disponibile al momento.');
    }
  });

  /* ------------------------------ Tastiera ------------------------------ */

  document.addEventListener('keydown', (e) => {
    const typing = e.target.closest('input, textarea');
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'z' && !(typing && e.target.type !== 'range')) {
      e.preventDefault();
      undo();
      return;
    }
    if (typing) return;
    const k = e.key.toLowerCase();
    if (k === 'escape') {
      tool = null;
      select(null);
      renderPath();
      renderPanel();
    } else if (k === 'd') setTool('path');
    else if (k === 's') setTool('start');
    else if (k === 'f') setTool('finish');
    else if (k === 'p') setTool('split');
    else if ((k === 'delete' || k === 'backspace') && selected) gateAction(selected, 'del');
  });

  /* ----------------------------- Tema e layout -------------------------- */

  function setTheme(t) {
    document.documentElement.dataset.theme = t;
    prefs.theme = t;
    savePrefs();
  }
  setTheme(prefs.theme || 'dark');
  $('btnTheme').addEventListener('click', () => setTheme(document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark'));
  $('btnCollapse').addEventListener('click', () => document.body.classList.toggle('collapsed'));

  /* -------------------------------- Avvio ------------------------------- */

  setLayer(layerName);
  try {
    const draft = JSON.parse(localStorage.getItem(DRAFT_KEY));
    if (draft && draft.track && !isEmpty(draft.track)) {
      track = Object.assign(TC.emptyTrack(), draft.track);
      toast('Bozza ripristinata');
    }
  } catch (e) { /* bozza non valida: si parte da zero */ }
  renderAll();
  if (!isEmpty(track)) setTimeout(fitTrack, 100);

  // Per i test automatici.
  window.EditorApp = {
    getTrack: () => clone(track),
    setTrack: (t) => { track = Object.assign(TC.emptyTrack(), clone(t)); selected = null; renderAll(); },
    setTool,
    importText,
    map,
  };
})();
