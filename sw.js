/*
 * sw.js — Service worker: cache dell'app shell per l'uso offline.
 * Le tile della mappa e le librerie CDN sono gestite in rete con fallback
 * alla cache (stale-while-revalidate semplificato).
 */
'use strict';

const CACHE = 'gpstelemetry-v1';
const SHELL = [
  './',
  './index.html',
  './css/style.css',
  './js/geo.js',
  './js/store.js',
  './js/recorder.js',
  './js/timing.js',
  './js/export.js',
  './js/app.js',
  './manifest.webmanifest',
  './icons/icon.svg',
];

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (e) => {
  const req = e.request;
  if (req.method !== 'GET') return;
  e.respondWith(
    caches.match(req).then((cached) => {
      const net = fetch(req)
        .then((res) => {
          if (res && res.ok && (req.url.startsWith(self.location.origin) || req.url.includes('unpkg.com'))) {
            const copy = res.clone();
            caches.open(CACHE).then((c) => c.put(req, copy));
          }
          return res;
        })
        .catch(() => cached);
      return cached || net;
    })
  );
});
