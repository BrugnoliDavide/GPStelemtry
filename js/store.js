/*
 * store.js — Persistenza locale su IndexedDB.
 * Due archivi: "tracks" (tracciati definiti) e "sessions" (sessioni cronometrate
 * con telemetria completa). Le sessioni possono contenere decine di migliaia di
 * campioni, quindi localStorage non è adeguato.
 */
'use strict';

const Store = (() => {
  const DB_NAME = 'gps-telemetry';
  const DB_VERSION = 1;
  let dbPromise = null;

  function open() {
    if (dbPromise) return dbPromise;
    dbPromise = new Promise((resolve, reject) => {
      const req = indexedDB.open(DB_NAME, DB_VERSION);
      req.onupgradeneeded = () => {
        const db = req.result;
        if (!db.objectStoreNames.contains('tracks')) {
          db.createObjectStore('tracks', { keyPath: 'id' });
        }
        if (!db.objectStoreNames.contains('sessions')) {
          const s = db.createObjectStore('sessions', { keyPath: 'id' });
          s.createIndex('trackId', 'trackId', { unique: false });
        }
      };
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });
    return dbPromise;
  }

  function tx(storeName, mode, fn) {
    return open().then(
      (db) =>
        new Promise((resolve, reject) => {
          const t = db.transaction(storeName, mode);
          const store = t.objectStore(storeName);
          const out = fn(store);
          t.oncomplete = () => resolve(out && out.result !== undefined ? out.result : undefined);
          t.onerror = () => reject(t.error);
          t.onabort = () => reject(t.error);
        })
    );
  }

  function getAll(storeName) {
    return open().then(
      (db) =>
        new Promise((resolve, reject) => {
          const req = db.transaction(storeName, 'readonly').objectStore(storeName).getAll();
          req.onsuccess = () => resolve(req.result || []);
          req.onerror = () => reject(req.error);
        })
    );
  }

  function get(storeName, id) {
    return open().then(
      (db) =>
        new Promise((resolve, reject) => {
          const req = db.transaction(storeName, 'readonly').objectStore(storeName).get(id);
          req.onsuccess = () => resolve(req.result || null);
          req.onerror = () => reject(req.error);
        })
    );
  }

  const put = (storeName, obj) => tx(storeName, 'readwrite', (s) => s.put(obj));
  const remove = (storeName, id) => tx(storeName, 'readwrite', (s) => s.delete(id));

  const uid = () =>
    Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 8);

  return {
    uid,
    listTracks: () => getAll('tracks'),
    getTrack: (id) => get('tracks', id),
    saveTrack: (t) => put('tracks', t),
    deleteTrack: (id) => remove('tracks', id),
    listSessions: () => getAll('sessions'),
    getSession: (id) => get('sessions', id),
    saveSession: (s) => put('sessions', s),
    deleteSession: (id) => remove('sessions', id),
  };
})();
