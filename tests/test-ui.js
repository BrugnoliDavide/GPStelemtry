const { chromium } = require('playwright');
const http = require('http');
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.webmanifest': 'application/manifest+json' };

const server = http.createServer((req, res) => {
  let p = req.url.split('?')[0];
  if (p === '/') p = '/index.html';
  const f = path.join(ROOT, p);
  if (!f.startsWith(ROOT) || !fs.existsSync(f)) { res.writeHead(404); res.end(); return; }
  res.writeHead(200, { 'Content-Type': MIME[path.extname(f)] || 'application/octet-stream' });
  res.end(fs.readFileSync(f));
});

const lat0 = 45.0, lon0 = 9.0;
const mPerDegLon = 111320 * Math.cos(lat0 * Math.PI / 180);
const geo = (x) => ({ latitude: lat0, longitude: lon0 + x / mPerDegLon, accuracy: 5 });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let failures = 0;
const assert = (c, msg) => { if (!c) { console.error('FALLITO:', msg); failures++; } else console.log('OK:', msg); };

(async () => {
  await new Promise((r) => server.listen(8099, r));
  const browser = await chromium.launch();
  const context = await browser.newContext({
    viewport: { width: 390, height: 780 },
    geolocation: geo(-60),
    permissions: ['geolocation'],
  });
  const page = await context.newPage();
  const errors = [];
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
  page.on('pageerror', (e) => errors.push(String(e)));

  await page.goto('http://localhost:8099/');
  await sleep(1200);
  assert(await page.isVisible('#view-tracks'), 'vista Tracciati visibile al caricamento');

  // --- Creazione tracciato in presa diretta (aperto, con un parziale) ---
  await page.click('#btn-new-track');
  await page.fill('#ed-name', 'Rettilineo di prova');
  await page.selectOption('#ed-type', 'open');
  await page.selectOption('#ed-mode', 'live');
  await page.click('#btn-live-start');
  await sleep(400);
  for (let x = -60; x <= 500; x += 50) {
    await context.setGeolocation(geo(x));
    await sleep(150);
    if (x === 240) { await page.click('#btn-live-split'); await sleep(100); }
  }
  await page.click('#btn-live-stop');
  await sleep(300);
  await page.click('#btn-save-track');
  await sleep(500);
  const trackCards = await page.locator('#track-list .card').count();
  assert(trackCards === 1, `tracciato salvato e in elenco (schede: ${trackCards})`);

  // --- Sessione cronometrata ---
  await context.setGeolocation(geo(-80));
  await page.click('.tabbar .tab[data-view="timer"]');
  await sleep(300);
  await page.click('#btn-session-start');
  await sleep(500);
  assert(await page.isVisible('#timer-live'), 'sessione avviata (telemetria in registrazione)');

  for (let x = -80; x <= 560; x += 40) {
    await context.setGeolocation(geo(x));
    await sleep(150);
  }
  await sleep(600);
  const state = await page.textContent('#timer-state');
  assert(/ARRIVO/.test(state), `cronometro arrestato al traguardo (stato: "${state.trim()}")`);
  const splits = await page.locator('#timer-splits li').count();
  assert(splits === 1, `parziale rilevato in sessione (${splits})`);

  // --- Vista sessioni ed esportazione GPX ---
  await page.click('.tabbar .tab[data-view="sessions"]');
  await sleep(400);
  const sessCards = await page.locator('#session-list .card').count();
  assert(sessCards === 1, `sessione salvata in elenco (${sessCards})`);
  const [download] = await Promise.all([
    page.waitForEvent('download'),
    page.locator('#session-list [data-act="gpx"]').first().click(),
  ]);
  const gpxPath = await download.path();
  const gpx = fs.readFileSync(gpxPath, 'utf8');
  assert(gpx.includes('<gpx version="1.1"'), 'GPX 1.1 generato');
  assert(gpx.includes('<trkpt lat="45.0000000"'), 'GPX contiene i punti traccia');
  assert(gpx.includes('<time>'), 'GPX contiene i timestamp');
  assert(gpx.includes('Partenza') && gpx.includes('Traguardo'), 'GPX contiene i waypoint di partenza/traguardo');

  const realErrors = errors.filter((e) => !/tile.openstreetmap|unpkg.com|Failed to load resource/.test(e));
  assert(realErrors.length === 0, 'nessun errore JavaScript in console' + (realErrors.length ? ': ' + realErrors.join(' | ') : ''));

  await browser.close();
  server.close();
  console.log(failures ? '\n*** TEST UI FALLITI ***' : '\nTutti i test UI superati.');
  process.exit(failures ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(1); });
