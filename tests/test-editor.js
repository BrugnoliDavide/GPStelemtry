/*
 * Test end-to-end dell'editor web dei tracciati (editor/), in Chromium headless.
 * Richiede Node con Playwright. Uso: node tests/test-editor.js
 */
const { chromium } = require('playwright');
const http = require('http');
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml' };
const server = http.createServer((req, res) => {
  let p = req.url.split('?')[0];
  if (p.endsWith('/')) p += 'index.html';
  const f = path.join(ROOT, p);
  if (!f.startsWith(ROOT) || !fs.existsSync(f)) { res.writeHead(404); res.end(); return; }
  res.writeHead(200, { 'Content-Type': MIME[path.extname(f)] || 'application/octet-stream' });
  res.end(fs.readFileSync(f));
});

let failures = 0;
const assert = (c, msg) => { if (!c) { console.error('FALLITO:', msg); failures++; } else console.log('OK:', msg); };

// GPX nel formato esportato dall'app Android (Gpx.track, con estensioni gpstel).
const ANDROID_GPX = `<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="GPSTelemetry"
  xmlns="http://www.topografix.com/GPX/1/1"
  xmlns:gpstel="https://gpstelemetry.app/xmlschemas/track/v1">
  <metadata><name>Salita del Passo</name><time>2026-11-13T10:00:00.000Z</time><extensions><gpstel:track type="OPEN" tolerance="12.0"/></extensions></metadata>
  <wpt lat="45.0100000" lon="9.0100000"><name>Partenza</name><extensions><gpstel:gate role="start" heading="33.50" width="28.00"/></extensions></wpt>
  <wpt lat="45.0200000" lon="9.0300000"><name>Traguardo</name><extensions><gpstel:gate role="finish" heading="120.00" width="24.00"/></extensions></wpt>
  <wpt lat="45.0150000" lon="9.0200000"><name>Tornante</name><extensions><gpstel:gate role="split" heading="77.00" width="20.00"/></extensions></wpt>
  <trk><name>Salita del Passo</name><trkseg>
    <trkpt lat="45.0100000" lon="9.0100000"><ele>400.0</ele></trkpt>
    <trkpt lat="45.0150000" lon="9.0200000"><ele>520.0</ele></trkpt>
    <trkpt lat="45.0200000" lon="9.0300000"><ele>640.0</ele></trkpt>
  </trkseg></trk>
</gpx>
`;

(async () => {
  await new Promise((r) => server.listen(8098, r));
  const browser = await chromium.launch();
  const context = await browser.newContext({ viewport: { width: 1280, height: 800 }, acceptDownloads: true });
  // Niente rete esterna per le tile (test veloce e deterministico): Leaflet sì.
  await context.route(/arcgisonline|tile\.openstreetmap|nominatim/, (route) => route.abort());
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));

  await page.goto('http://localhost:8098/editor/');
  await page.waitForFunction(() => window.EditorApp && window.L);
  assert(true, 'editor caricato');

  // Disegno del percorso con lo strumento "Percorso".
  await page.click('.tool[data-tool="path"]');
  const pts = [[700, 300], [900, 320], [950, 500], [720, 520]];
  for (const [x, y] of pts) await page.mouse.click(x, y);
  let t = await page.evaluate(() => EditorApp.getTrack());
  assert(t.path.length === 4, `percorso disegnato con 4 punti (${t.path.length})`);

  // Annulla con Ctrl+Z.
  await page.keyboard.press('Control+z');
  t = await page.evaluate(() => EditorApp.getTrack());
  assert(t.path.length === 3, 'Ctrl+Z annulla l\'ultimo punto');
  await page.mouse.click(720, 520);

  // Partenza sul primo tratto: perpendicolare al percorso.
  await page.click('.tool[data-tool="start"]');
  // Posizionata la partenza la mappa si avvicina: si attende la fine
  // dell'animazione perché i clic successivi siano deterministici.
  const settled = page.evaluate(() => new Promise((r) => EditorApp.map.once('moveend', () => setTimeout(r, 100))));
  await page.mouse.click(800, 310);
  await settled;
  t = await page.evaluate(() => EditorApp.getTrack());
  assert(t.start !== null, 'linea di partenza posizionata');
  const segHeading = await page.evaluate((tr) => TrackCore.bearing(tr.path[0], tr.path[1]), t);
  const diff = Math.abs(((t.start.heading - segHeading) % 180 + 180) % 180 - 90);
  assert(diff < 2, `partenza perpendicolare al percorso (scarto ${diff.toFixed(2)}°)`);
  assert(await page.locator('.gate-card').count() === 1, 'scheda della linea nel pannello');

  // Parziale + nome; esportazione senza nome bloccata.
  await page.click('.tool[data-tool="split"]');
  await page.mouse.click(930, 410);
  await page.keyboard.press('Escape');
  await page.click('#btnExport');
  const errText = await page.textContent('#errors');
  assert(/nome/i.test(errText), 'senza nome l\'esportazione è bloccata con un messaggio');
  await page.fill('#trackName', 'Pista web');

  // Esportazione: file scaricato nel formato dell'app, reimportabile identico.
  const [download] = await Promise.all([page.waitForEvent('download'), page.click('#btnExport')]);
  assert(download.suggestedFilename() === 'Pista_web.gpx', `nome file ${download.suggestedFilename()}`);
  const gpx = fs.readFileSync(await download.path(), 'utf8');
  assert(gpx.includes('gpstel:gate role="start"') && gpx.includes('gpstel:track type="CIRCULAR"'), 'GPX con estensioni gpstel');
  const back = await page.evaluate((x) => TrackCore.fromGpx(x), gpx);
  const now = await page.evaluate(() => EditorApp.getTrack());
  assert(back.name === 'Pista web' && back.splits.length === 1 && back.path.length === now.path.length, 'reimportazione: nome, parziali e percorso');
  assert(Math.abs(back.start.heading - now.start.heading) < 0.01 && Math.abs(back.start.width - now.start.width) < 0.01, 'reimportazione: orientamento e larghezza');

  // Bozza ripristinata dopo il ricaricamento.
  await page.waitForTimeout(500);
  await page.reload();
  await page.waitForFunction(() => window.EditorApp);
  t = await page.evaluate(() => EditorApp.getTrack());
  assert(t.name === 'Pista web' && t.path.length === 4, 'bozza ripristinata dopo il ricaricamento');

  // Importazione di un GPX esportato dall'app Android (sostituzione confermata).
  page.once('dialog', (d) => d.accept());
  await page.setInputFiles('#fileInput', { name: 'salita.gpx', mimeType: 'application/gpx+xml', buffer: Buffer.from(ANDROID_GPX) });
  await page.waitForFunction(() => EditorApp.getTrack().name === 'Salita del Passo');
  t = await page.evaluate(() => EditorApp.getTrack());
  assert(t.type === 'OPEN' && t.tolerance === 12, 'import app: tipo e tolleranza');
  assert(t.start.heading === 33.5 && t.finish.width === 24 && t.splits[0].name === 'Tornante', 'import app: linee identiche');
  assert(t.path[2].ele === 640, 'import app: quote del percorso');
  assert(await page.inputValue('#trackName') === 'Salita del Passo', 'pannello aggiornato dopo l\'importazione');

  // File non valido: nessuna modifica.
  await page.setInputFiles('#fileInput', { name: 'x.gpx', mimeType: 'text/plain', buffer: Buffer.from('non è un gpx') });
  await page.waitForTimeout(300);
  t = await page.evaluate(() => EditorApp.getTrack());
  assert(t.name === 'Salita del Passo', 'file non valido ignorato');
  assert(/Impossibile importare/.test(await page.textContent('#toast')), 'messaggio d\'errore per file non valido');

  assert(errors.length === 0, `nessun errore JavaScript (${errors.join('; ')})`);

  // Copia del GPX esportato per il test di compatibilità lato app (Kotlin).
  const fixture = path.join(ROOT, 'android/core/src/test/resources/web-editor-export.gpx');
  // Riscritto solo se cambia davvero (la data di esportazione non conta).
  const noTime = (s) => s.replace(/<time>[^<]*<\/time>/, '');
  const old = fs.existsSync(fixture) ? fs.readFileSync(fixture, 'utf8') : null;
  if (old === null || noTime(old) !== noTime(gpx)) {
    fs.mkdirSync(path.dirname(fixture), { recursive: true });
    fs.writeFileSync(fixture, gpx);
  }

  await browser.close();
  server.close();
  if (failures) {
    console.error(`\n${failures} test falliti.`);
    process.exit(1);
  }
  console.log('\nTutti i test dell\'editor web superati.');
})().catch((e) => {
  console.error(e);
  process.exit(1);
});
