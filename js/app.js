/*
 * app.js — Logica dell'interfaccia utente.
 */
'use strict';

(() => {
  const $ = (id) => document.getElementById(id);
  const show = (el) => el.classList.remove('hidden');
  const hide = (el) => el.classList.add('hidden');

  /* ------------------------------------------------------------------ */
  /* Utilità                                                             */
  /* ------------------------------------------------------------------ */

  function toast(msg, ms = 3000) {
    const t = $('toast');
    t.textContent = msg;
    show(t);
    clearTimeout(t._h);
    t._h = setTimeout(() => hide(t), ms);
  }

  function fmtTime(ms) {
    if (ms == null) return '—';
    const cs = Math.floor(ms / 100) % 10;
    const s = Math.floor(ms / 1000) % 60;
    const m = Math.floor(ms / 60000) % 60;
    const h = Math.floor(ms / 3600000);
    const mm = String(m).padStart(2, '0');
    const ss = String(s).padStart(2, '0');
    return (h ? h + ':' + mm : mm) + ':' + ss + '.' + cs;
  }

  function fmtDist(m) {
    return m >= 1000 ? (m / 1000).toFixed(2) + ' km' : Math.round(m) + ' m';
  }

  const hasLeaflet = () => typeof L !== 'undefined';

  /* ------------------------------------------------------------------ */
  /* Navigazione tra viste                                               */
  /* ------------------------------------------------------------------ */

  const views = { tracks: $('view-tracks'), editor: $('view-editor'), timer: $('view-timer'), sessions: $('view-sessions') };

  function switchView(name) {
    Object.values(views).forEach(hide);
    show(views[name]);
    document.querySelectorAll('.tabbar .tab').forEach((b) => {
      b.classList.toggle('active', b.dataset.view === name);
    });
    if (name === 'tracks') renderTracks();
    if (name === 'sessions') renderSessions();
    if (name === 'timer') populateTimerTracks();
    if (name === 'editor' && editor.map) setTimeout(() => editor.map.invalidateSize(), 50);
  }

  document.querySelectorAll('.tabbar .tab').forEach((b) => {
    b.addEventListener('click', () => {
      if (timer.engine && timer.recorder && timer.recorder.running && b.dataset.view !== 'timer') {
        toast('Sessione in corso: interrompila prima di cambiare vista.');
        return;
      }
      switchView(b.dataset.view);
    });
  });

  /* ------------------------------------------------------------------ */
  /* Vista TRACCIATI                                                     */
  /* ------------------------------------------------------------------ */

  async function renderTracks() {
    const list = $('track-list');
    const tracks = await Store.listTracks();
    tracks.sort((a, b) => b.createdAt - a.createdAt);
    list.innerHTML = '';
    $('tracks-empty').classList.toggle('hidden', tracks.length > 0);
    for (const t of tracks) {
      const li = document.createElement('li');
      li.className = 'card';
      const len = t.path && t.path.length > 1 ? fmtDist(Geo.pathLength(t.path)) : '—';
      li.innerHTML =
        `<div class="card-main">
           <strong>${escapeHtml(t.name)}</strong>
           <small>${t.type === 'circular' ? 'Circolare' : 'Aperto'} · lunghezza ${len} ·
             tolleranza ${t.tolerance} m · parziali: ${(t.splits || []).length}</small>
         </div>
         <div class="card-actions">
           <button class="btn small" data-act="gpx">GPX</button>
           <button class="btn small danger" data-act="del">Elimina</button>
         </div>`;
      li.querySelector('[data-act="gpx"]').addEventListener('click', () => {
        Exporter.download(Exporter.safeName(t.name) + '.gpx', Exporter.trackToGPX(t), 'application/gpx+xml');
      });
      li.querySelector('[data-act="del"]').addEventListener('click', async () => {
        if (confirm(`Eliminare il tracciato “${t.name}”?`)) {
          await Store.deleteTrack(t.id);
          renderTracks();
        }
      });
      list.appendChild(li);
    }
  }

  function escapeHtml(s) {
    const d = document.createElement('div');
    d.textContent = s == null ? '' : String(s);
    return d.innerHTML;
  }

  /* ------------------------------------------------------------------ */
  /* Vista EDITOR TRACCIATO                                              */
  /* ------------------------------------------------------------------ */

  const editor = {
    map: null,
    picking: null, // 'start' | 'finish' | 'split'
    start: null,
    finish: null,
    splits: [],
    path: [],
    layers: { start: null, finish: null, splits: [], path: null },
    walk: null, // Recorder per la percorrenza GPS
    live: null, // Recorder per la presa diretta
    liveSplits: [],
  };

  $('btn-new-track').addEventListener('click', () => {
    resetEditor();
    switchView('editor');
    initEditorMap();
  });

  $('btn-editor-back').addEventListener('click', () => {
    stopEditorRecorders();
    switchView('tracks');
  });

  function resetEditor() {
    $('ed-name').value = '';
    $('ed-type').value = 'circular';
    $('ed-tolerance').value = '15';
    $('ed-mode').value = 'map';
    editor.picking = null;
    editor.start = null;
    editor.finish = null;
    editor.splits = [];
    editor.path = [];
    editor.liveSplits = [];
    clearEditorLayers();
    updateEditorPanels();
    updateEditorSummary();
    $('ed-walk-status').textContent = '';
    hide($('btn-walk-stop'));
    show($('btn-walk-path'));
    show($('btn-live-start'));
    hide($('btn-live-split'));
    hide($('btn-live-stop'));
    hide($('ed-live-stats'));
  }

  function stopEditorRecorders() {
    if (editor.walk) { editor.walk.stop(); editor.walk = null; }
    if (editor.live) { editor.live.stop(); editor.live = null; }
  }

  function initEditorMap() {
    if (!hasLeaflet()) {
      $('ed-map-hint').textContent =
        'Mappa non disponibile (manca la connessione alla libreria Leaflet). Usa la modalità “Presa diretta”.';
      return;
    }
    if (!editor.map) {
      editor.map = L.map('map', { zoomControl: true }).setView([45.4642, 9.19], 13);
      L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
        maxZoom: 19,
        attribution: '&copy; OpenStreetMap',
      }).addTo(editor.map);
      editor.map.on('click', (e) => onMapPick(e.latlng));
      // Centra sulla posizione corrente, se disponibile.
      navigator.geolocation?.getCurrentPosition(
        (pos) => editor.map.setView([pos.coords.latitude, pos.coords.longitude], 16),
        () => {},
        { enableHighAccuracy: true, timeout: 8000 }
      );
    }
    setTimeout(() => editor.map.invalidateSize(), 50);
  }

  function onMapPick(latlng) {
    const p = { lat: latlng.lat, lon: latlng.lng };
    if (editor.picking === 'start') {
      editor.start = p;
      editor.picking = null;
    } else if (editor.picking === 'finish') {
      editor.finish = p;
      editor.picking = null;
    } else if (editor.picking === 'split') {
      editor.splits.push({ name: `Parziale ${editor.splits.length + 1}`, ...p });
    } else {
      return;
    }
    redrawEditorLayers();
    updateEditorSummary();
    updatePickButtons();
  }

  document.querySelectorAll('[data-pick]').forEach((b) => {
    b.addEventListener('click', () => {
      const kind = b.dataset.pick;
      if (kind === 'finish' && $('ed-type').value === 'circular') {
        toast('Tracciato circolare: il traguardo coincide con la partenza.');
        return;
      }
      editor.picking = editor.picking === kind ? null : kind;
      updatePickButtons();
      $('ed-map-hint').textContent = editor.picking
        ? (editor.picking === 'split'
            ? 'Tocca la mappa per aggiungere parziali (nell’ordine di percorrenza). Ripremi il pulsante per terminare.'
            : 'Tocca la mappa per posizionare: ' + (editor.picking === 'start' ? 'partenza' : 'traguardo') + '.')
        : 'Seleziona un elemento e tocca la mappa per posizionarlo.';
    });
  });

  function updatePickButtons() {
    document.querySelectorAll('[data-pick]').forEach((b) => {
      b.classList.toggle('active', editor.picking === b.dataset.pick);
    });
  }

  $('btn-clear-points').addEventListener('click', () => {
    editor.start = null;
    editor.finish = null;
    editor.splits = [];
    editor.path = [];
    clearEditorLayers();
    updateEditorSummary();
  });

  function clearEditorLayers() {
    if (!editor.map) return;
    const ly = editor.layers;
    [ly.start, ly.finish, ly.path, ...ly.splits].forEach((l) => l && editor.map.removeLayer(l));
    editor.layers = { start: null, finish: null, splits: [], path: null };
  }

  function redrawEditorLayers() {
    if (!editor.map) return;
    clearEditorLayers();
    const ly = editor.layers;
    if (editor.start) {
      ly.start = L.marker([editor.start.lat, editor.start.lon], { title: 'Partenza' })
        .addTo(editor.map).bindPopup('Partenza');
    }
    if (editor.finish) {
      ly.finish = L.marker([editor.finish.lat, editor.finish.lon], { title: 'Traguardo' })
        .addTo(editor.map).bindPopup('Traguardo');
    }
    editor.splits.forEach((sp, i) => {
      const m = L.circleMarker([sp.lat, sp.lon], { radius: 7, color: '#f59e0b' })
        .addTo(editor.map).bindPopup(sp.name || `Parziale ${i + 1}`);
      ly.splits.push(m);
    });
    if (editor.path.length > 1) {
      ly.path = L.polyline(editor.path.map((p) => [p.lat, p.lon]), { color: '#3b82f6', weight: 4 })
        .addTo(editor.map);
    }
  }

  $('ed-mode').addEventListener('change', updateEditorPanels);
  $('ed-type').addEventListener('change', () => {
    if ($('ed-type').value === 'circular') {
      editor.finish = null;
      redrawEditorLayers();
    }
    $('btn-set-finish').classList.toggle('disabled', $('ed-type').value === 'circular');
    updateEditorSummary();
  });

  function updateEditorPanels() {
    const mode = $('ed-mode').value;
    $('ed-map-panel').classList.toggle('hidden', mode !== 'map');
    $('ed-live-panel').classList.toggle('hidden', mode !== 'live');
    if (mode === 'map') initEditorMap();
  }

  function updateEditorSummary() {
    const parts = [];
    parts.push(editor.start ? '✅ partenza definita' : '⬜ partenza mancante');
    if ($('ed-type').value === 'open') {
      parts.push(editor.finish ? '✅ traguardo definito' : '⬜ traguardo mancante');
    }
    parts.push(`parziali: ${editor.splits.length}`);
    parts.push(editor.path.length > 1
      ? `✅ percorso registrato (${editor.path.length} punti, ${fmtDist(Geo.pathLength(editor.path))})`
      : '⬜ percorso non registrato (il controllo di tolleranza sul corridoio richiede il percorso)');
    $('ed-summary').innerHTML = parts.join(' · ');
  }

  /* --- Percorrenza GPS del percorso (modalità mappa) --- */

  $('btn-walk-path').addEventListener('click', async () => {
    if (!editor.start) {
      toast('Definisci prima la partenza sulla mappa.');
      return;
    }
    await Recorder.requestMotionPermission();
    editor.walk = new Recorder();
    editor.walk.onFix = (fix) => {
      editor.path.push({ lat: fix.lat, lon: fix.lon, ele: fix.ele });
      $('ed-walk-status').textContent =
        `Registrazione… ${editor.path.length} punti, ${fmtDist(Geo.pathLength(editor.path))}`;
      if (editor.map && editor.path.length % 5 === 0) redrawEditorLayers();
    };
    editor.walk.onError = (m) => toast(m);
    try {
      editor.path = [];
      await editor.walk.start();
      hide($('btn-walk-path'));
      show($('btn-walk-stop'));
      $('ed-walk-status').textContent = 'In attesa del primo fix GPS…';
    } catch (e) {
      toast(e.message);
    }
  });

  $('btn-walk-stop').addEventListener('click', () => {
    if (editor.walk) { editor.walk.stop(); editor.walk = null; }
    editor.path = Geo.simplify(editor.path, 2);
    show($('btn-walk-path'));
    hide($('btn-walk-stop'));
    $('ed-walk-status').textContent = `Percorso registrato: ${editor.path.length} punti.`;
    redrawEditorLayers();
    updateEditorSummary();
  });

  /* --- Presa diretta --- */

  $('btn-live-start').addEventListener('click', async () => {
    await Recorder.requestMotionPermission();
    editor.live = new Recorder();
    editor.liveSplits = [];
    editor.path = [];
    editor.live.onFix = (fix) => {
      editor.path.push({ lat: fix.lat, lon: fix.lon, ele: fix.ele });
      $('live-points').textContent = String(editor.path.length);
      $('live-dist').textContent = fmtDist(Geo.pathLength(editor.path));
      $('live-acc').textContent = fix.acc != null ? fix.acc + ' m' : '—';
    };
    editor.live.onError = (m) => toast(m);
    try {
      await editor.live.start();
      hide($('btn-live-start'));
      show($('btn-live-split'));
      show($('btn-live-stop'));
      show($('ed-live-stats'));
      toast('Cattura avviata: la partenza sarà il primo punto GPS rilevato.');
    } catch (e) {
      toast(e.message);
    }
  });

  $('btn-live-split').addEventListener('click', () => {
    const fix = editor.live && editor.live.lastFix();
    if (!fix) {
      toast('Nessuna posizione GPS disponibile per il parziale.');
      return;
    }
    editor.liveSplits.push({
      name: `Parziale ${editor.liveSplits.length + 1}`,
      lat: fix.lat,
      lon: fix.lon,
    });
    $('live-splits').textContent = String(editor.liveSplits.length);
    toast(`Parziale ${editor.liveSplits.length} contrassegnato.`);
  });

  $('btn-live-stop').addEventListener('click', () => {
    if (!editor.live) return;
    editor.live.stop();
    const pts = editor.path;
    if (pts.length < 2) {
      toast('Punti GPS insufficienti: cattura annullata.');
    } else {
      editor.start = { lat: pts[0].lat, lon: pts[0].lon };
      editor.finish = $('ed-type').value === 'open'
        ? { lat: pts[pts.length - 1].lat, lon: pts[pts.length - 1].lon }
        : null;
      editor.splits = editor.liveSplits.slice();
      editor.path = Geo.simplify(pts, 2);
      toast('Cattura completata. Verifica i dati e salva il tracciato.');
    }
    editor.live = null;
    show($('btn-live-start'));
    hide($('btn-live-split'));
    hide($('btn-live-stop'));
    updateEditorSummary();
  });

  /* --- Salvataggio --- */

  $('btn-save-track').addEventListener('click', async () => {
    const name = $('ed-name').value.trim();
    const type = $('ed-type').value;
    const tolerance = Math.max(5, Math.min(100, Number($('ed-tolerance').value) || 15));
    if (!name) { toast('Indica un nome per il tracciato.'); return; }
    if (!editor.start) { toast('Partenza non definita.'); return; }
    if (type === 'open' && !editor.finish) { toast('Traguardo non definito (tracciato aperto).'); return; }
    if (editor.walk || editor.live) { toast('Termina prima la registrazione in corso.'); return; }

    const track = {
      id: Store.uid(),
      name,
      type,
      tolerance,
      createdAt: Date.now(),
      start: editor.start,
      finish: type === 'open' ? editor.finish : null,
      splits: editor.splits,
      path: editor.path,
    };
    await Store.saveTrack(track);
    toast('Tracciato salvato.');
    switchView('tracks');
  });

  /* ------------------------------------------------------------------ */
  /* Vista CRONOMETRO                                                    */
  /* ------------------------------------------------------------------ */

  const timer = {
    recorder: null,
    engine: null,
    track: null,
    session: null,
    rafId: null,
  };

  async function populateTimerTracks() {
    const sel = $('timer-track');
    const tracks = await Store.listTracks();
    tracks.sort((a, b) => b.createdAt - a.createdAt);
    sel.innerHTML = tracks.length
      ? tracks.map((t) => `<option value="${t.id}">${escapeHtml(t.name)}</option>`).join('')
      : '<option value="">(nessun tracciato definito)</option>';
  }

  $('btn-session-start').addEventListener('click', async () => {
    const trackId = $('timer-track').value;
    if (!trackId) { toast('Definisci prima un tracciato.'); return; }
    const track = await Store.getTrack(trackId);
    if (!track) { toast('Tracciato non trovato.'); return; }

    await Recorder.requestMotionPermission();
    timer.track = track;
    timer.session = {
      id: Store.uid(),
      trackId: track.id,
      name: `${track.name} — ${new Date().toLocaleString('it-IT')}`,
      startedAt: Date.now(),
      trackRef: { type: track.type, start: track.start, finish: track.finish, splits: track.splits },
      samples: [],
      motion: [],
      result: null,
      status: 'armed',
    };
    timer.engine = new TimingEngine(track, onRaceEvent);
    timer.recorder = new Recorder();
    timer.recorder.onFix = onTimerFix;
    timer.recorder.onError = (m) => toast(m);
    try {
      await timer.recorder.start();
    } catch (e) {
      toast(e.message);
      timer.recorder = null;
      timer.engine = null;
      return;
    }
    hide($('timer-setup'));
    show($('timer-live'));
    $('timer-splits').innerHTML = '';
    hide($('offtrack-banner'));
    $('timer-state').textContent = 'IN ATTESA DEL GPS…';
    $('timer-state').className = 'timer-state waiting';
    tickClock();
  });

  function onTimerFix(fix) {
    $('gps-indicator').classList.add('on');
    timer.session.samples.push(fix);
    timer.engine.feed(fix);

    $('tm-speed').textContent = fix.speed != null ? (fix.speed * 3.6).toFixed(1) + ' km/h' : '—';
    $('tm-ele').textContent = fix.ele != null ? fix.ele + ' m' : '—';
    $('tm-acc').textContent = fix.acc != null ? fix.acc + ' m' : '—';

    const mo = timer.recorder.motion;
    if (mo.length) {
      const m = mo[mo.length - 1];
      $('tm-pitch').textContent = m.pitch != null ? m.pitch + '°' : '—';
      const g = Math.sqrt(m.ax * m.ax + m.ay * m.ay + m.az * m.az) / 9.81;
      $('tm-gforce').textContent = g.toFixed(2) + ' g';
    }
    $('tm-offtime').textContent = (timer.engine.summary().offTrackMs / 1000).toFixed(1) + ' s';
    $('tm-offcount').textContent = String(timer.engine.offTrackEpisodes);

    if (timer.engine.state === 'armed') {
      const d = Geo.haversine(fix, timer.track.start);
      $('timer-state').textContent = `PRONTO — partenza a ${Math.round(d)} m`;
      $('timer-state').className = 'timer-state armed';
    }
  }

  function onRaceEvent(ev) {
    if (ev.type === 'start') {
      $('timer-state').textContent = 'IN CORSA';
      $('timer-state').className = 'timer-state running';
      if (navigator.vibrate) navigator.vibrate(200);
    } else if (ev.type === 'split') {
      const li = document.createElement('li');
      li.textContent = `${ev.name}: ${fmtTime(ev.elapsed)}`;
      $('timer-splits').appendChild(li);
      if (navigator.vibrate) navigator.vibrate([100, 50, 100]);
    } else if (ev.type === 'offtrack-enter') {
      show($('offtrack-banner'));
      if (navigator.vibrate) navigator.vibrate([300, 100, 300]);
    } else if (ev.type === 'offtrack-exit') {
      hide($('offtrack-banner'));
    } else if (ev.type === 'finish') {
      $('timer-state').textContent = 'ARRIVO — ' + fmtTime(ev.elapsed);
      $('timer-state').className = 'timer-state finished';
      if (navigator.vibrate) navigator.vibrate([400, 100, 400]);
      finalizeSession('finished');
    }
  }

  function tickClock() {
    if (!timer.engine) return;
    $('timer-clock').textContent = fmtTime(timer.engine.startTime != null ? timer.engine.elapsed : 0);
    if (timer.engine.state !== 'finished') {
      timer.rafId = requestAnimationFrame(tickClock);
    }
  }

  $('btn-session-stop').addEventListener('click', () => {
    if (!timer.engine) return;
    const st = timer.engine.state;
    finalizeSession(st === 'finished' ? 'finished' : 'aborted');
  });

  async function finalizeSession(status) {
    if (!timer.session) return;
    if (timer.rafId) cancelAnimationFrame(timer.rafId);
    const rec = timer.recorder;
    if (rec) {
      rec.stop();
      timer.session.motion = rec.motion;
    }
    timer.session.result = timer.engine.summary();
    timer.session.status = status;
    await Store.saveSession(timer.session);

    const res = timer.session.result;
    if (status === 'finished') {
      toast(`Sessione completata: ${fmtTime(res.elapsedMs)} (fuori tolleranza ${(res.offTrackMs / 1000).toFixed(1)} s).`, 6000);
    } else {
      toast('Sessione interrotta e salvata.');
    }
    timer.recorder = null;
    timer.engine = null;
    timer.session = null;
    timer.track = null;
    show($('timer-setup'));
    hide($('timer-live'));
    $('gps-indicator').classList.remove('on');
  }

  /* ------------------------------------------------------------------ */
  /* Vista SESSIONI                                                      */
  /* ------------------------------------------------------------------ */

  async function renderSessions() {
    const list = $('session-list');
    const sessions = await Store.listSessions();
    sessions.sort((a, b) => b.startedAt - a.startedAt);
    list.innerHTML = '';
    $('sessions-empty').classList.toggle('hidden', sessions.length > 0);
    for (const s of sessions) {
      const li = document.createElement('li');
      li.className = 'card';
      const r = s.result || {};
      const splits = (r.splits || [])
        .map((sp) => `${escapeHtml(sp.name)}: ${fmtTime(sp.elapsed)}`)
        .join(' · ');
      li.innerHTML =
        `<div class="card-main">
           <strong>${escapeHtml(s.name)}</strong>
           <small>${s.status === 'finished' ? '⏱ ' + fmtTime(r.elapsedMs) : '⚠ interrotta'} ·
             fuori tolleranza ${((r.offTrackMs || 0) / 1000).toFixed(1)} s (${r.offTrackEpisodes || 0} uscite) ·
             ${(s.samples || []).length} punti GPS</small>
           ${splits ? `<small>${splits}</small>` : ''}
         </div>
         <div class="card-actions">
           <button class="btn small" data-act="gpx">GPX</button>
           <button class="btn small" data-act="csv">CSV</button>
           <button class="btn small" data-act="json">JSON</button>
           <button class="btn small danger" data-act="del">Elimina</button>
         </div>`;
      const base = Exporter.safeName(s.name);
      li.querySelector('[data-act="gpx"]').addEventListener('click', () => {
        Exporter.download(base + '.gpx', Exporter.sessionToGPX(s), 'application/gpx+xml');
      });
      li.querySelector('[data-act="csv"]').addEventListener('click', () => {
        Exporter.download(base + '_gps.csv', Exporter.gpsToCSV(s), 'text/csv');
        Exporter.download(base + '_motion.csv', Exporter.motionToCSV(s), 'text/csv');
      });
      li.querySelector('[data-act="json"]').addEventListener('click', () => {
        Exporter.download(base + '.json', JSON.stringify(s, null, 1), 'application/json');
      });
      li.querySelector('[data-act="del"]').addEventListener('click', async () => {
        if (confirm('Eliminare questa sessione?')) {
          await Store.deleteSession(s.id);
          renderSessions();
        }
      });
      list.appendChild(li);
    }
  }

  /* ------------------------------------------------------------------ */
  /* Avvio                                                               */
  /* ------------------------------------------------------------------ */

  if ('serviceWorker' in navigator && location.protocol === 'https:') {
    navigator.serviceWorker.register('sw.js').catch(() => {});
  }
  renderTracks();
})();
