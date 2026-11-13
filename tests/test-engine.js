// Test del motore di cronometraggio con dati GPS sintetici.
const assert = (c, msg) => { if (!c) { console.error('FALLITO:', msg); process.exitCode = 1; } else console.log('OK:', msg); };

// Percorso rettilineo verso est: 1° di lon a lat 45 ~ 78.7 km; usiamo metri -> gradi.
const lat = 45.0;
const mPerDegLon = 111320 * Math.cos(lat * Math.PI / 180);
const pt = (xMeters, yMeters = 0) => ({ lat: lat + yMeters / 111320, lon: 9 + xMeters / mPerDegLon });

// --- Test geo ---
const a = pt(0), b = pt(1000);
const d = Geo.haversine(a, b);
assert(Math.abs(d - 1000) < 2, `haversine ~1000 m (ottenuto ${d.toFixed(2)})`);
const dseg = Geo.distToSegment(pt(500, 30), a, b);
assert(Math.abs(dseg - 30) < 1, `distToSegment ~30 m (ottenuto ${dseg.toFixed(2)})`);

// --- Tracciato aperto: partenza x=0, traguardo x=1000, parziale x=500, tolleranza 10 m ---
const path = []; for (let x = 0; x <= 1000; x += 50) path.push(pt(x));
const track = { type: 'open', tolerance: 10, start: pt(0), finish: pt(1000),
                splits: [{ name: 'P1', ...pt(500) }], path };

const events = [];
const eng = new TimingEngine(track, (e) => events.push(e));

// Simulazione: 10 m/s da x=-100 a x=1100, fix ogni secondo; deviazione fuori corridoio tra x=700 e x=800 (30 m a lato).
let t0 = 1700000000000;
for (let i = 0; i <= 120; i++) {
  const x = -100 + i * 10;
  const y = (x >= 700 && x <= 800) ? 30 : 0;
  eng.feed({ ...pt(x, y), t: t0 + i * 1000 });
  if (eng.state === 'finished') break;
}

const s = eng.summary();
assert(events.some(e => e.type === 'start'), 'evento di partenza emesso');
assert(events.some(e => e.type === 'split' && e.name === 'P1'), 'parziale P1 rilevato');
assert(events.some(e => e.type === 'finish'), 'evento di traguardo emesso');
assert(events.some(e => e.type === 'offtrack-enter'), 'uscita dal corridoio rilevata');
assert(events.some(e => e.type === 'offtrack-exit'), 'rientro nel corridoio rilevato');
assert(s.offTrackEpisodes === 1, `una sola uscita (ottenute ${s.offTrackEpisodes})`);
assert(s.offTrackMs >= 8000 && s.offTrackMs <= 12000, `tempo fuori tolleranza ~10 s (ottenuto ${s.offTrackMs} ms)`);

// Tempo atteso: partenza a x=-10 (ingresso raggio 10 m) -> traguardo a x=990: 980 m a 10 m/s = 98 s.
assert(Math.abs(s.elapsedMs - 100000) < 1500, `tempo totale ~100 s (ottenuto ${(s.elapsedMs/1000).toFixed(2)} s)`);
const p1 = s.splits[0];
assert(Math.abs(p1.elapsed - 50000) < 1500, `parziale ~50 s (ottenuto ${(p1.elapsed/1000).toFixed(2)} s)`);

// --- Tracciato circolare: quadrato 400x400 m, partenza nell'angolo (0,0) ---
const sq = [];
for (let x = 0; x <= 400; x += 40) sq.push(pt(x, 0));
for (let y = 40; y <= 400; y += 40) sq.push(pt(400, y));
for (let x = 360; x >= 0; x -= 40) sq.push(pt(x, 400));
for (let y = 360; y >= 0; y -= 40) sq.push(pt(0, y));
const circ = { type: 'circular', tolerance: 12, start: pt(0, 0), finish: null, splits: [], path: sq };
const ev2 = [];
const eng2 = new TimingEngine(circ, (e) => ev2.push(e));
// Giro del perimetro a 8 m/s partendo da dentro la zona di partenza (da fermo).
let i2 = 0;
const perim = [];
for (let x = 0; x <= 400; x += 8) perim.push(pt(x, 0));
for (let y = 8; y <= 400; y += 8) perim.push(pt(400, y));
for (let x = 392; x >= 0; x -= 8) perim.push(pt(x, 400));
for (let y = 392; y >= 0; y -= 8) perim.push(pt(0, y));
for (const p of perim) {
  eng2.feed({ ...p, t: t0 + i2 * 1000 });
  i2++;
  if (eng2.state === 'finished') break;
}
const s2 = eng2.summary();
assert(ev2.some(e => e.type === 'start'), 'circolare: partenza emessa (uscita dalla zona)');
assert(ev2.some(e => e.type === 'finish'), 'circolare: traguardo emesso al ritorno');
assert(s2.offTrackEpisodes === 0, 'circolare: nessuna uscita dal corridoio');
// Perimetro 1600 m a 8 m/s = 200 s; partenza all'uscita del raggio 12 m, arrivo all'ingresso: ~197 s.
assert(Math.abs(s2.elapsedMs - 197000) < 3000, `circolare: tempo ~197 s (ottenuto ${(s2.elapsedMs/1000).toFixed(2)} s)`);
assert(!ev2.filter(e=>e.type==='finish').length || s2.elapsedMs > 15000, 'circolare: nessun arresto immediato');

console.log(process.exitCode ? '\n*** TEST FALLITI ***' : '\nTutti i test superati.');

// --- Alta velocità: fix ogni 40 m, cancelli di raggio 10 m "scavalcati" dai campioni ---
const fast = { type: 'open', tolerance: 10, start: pt(0), finish: pt(1000),
               splits: [{ name: 'P1', ...pt(500) }], path };
const ev3 = [];
const eng3 = new TimingEngine(fast, (e) => ev3.push(e));
for (let i = 0; i <= 40; i++) {
  const x = -100 + i * 40; // nessun campione entro 10 m dai cancelli (−100+k·40: min |x|=20)
  eng3.feed({ ...pt(x), t: t0 + i * 1000 });
  if (eng3.state === 'finished') break;
}
const s3 = eng3.summary();
assert(ev3.some(e => e.type === 'start'), 'alta velocità: partenza rilevata tra due fix');
assert(ev3.some(e => e.type === 'split'), 'alta velocità: parziale rilevato tra due fix');
assert(ev3.some(e => e.type === 'finish'), 'alta velocità: traguardo rilevato tra due fix');
// 40 m/s, da x=-10 (ingresso) a x=990 (ingresso): 1000 m -> 25 s
assert(s3.elapsedMs != null && Math.abs(s3.elapsedMs - 25000) < 600, `alta velocità: tempo ~25 s (ottenuto ${s3.elapsedMs != null ? (s3.elapsedMs/1000).toFixed(2) : 'null'} s)`);
