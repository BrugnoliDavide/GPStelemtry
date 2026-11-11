/*
 * recorder.js — Acquisizione telemetrica.
 * Raccoglie:
 *  - posizioni GPS ad alta precisione (watchPosition);
 *  - accelerometro (DeviceMotion), sottocampionato a ~10 Hz;
 *  - inclinazione pitch/roll (DeviceOrientation);
 * e mantiene attivo lo schermo tramite Wake Lock quando disponibile.
 *
 * I campioni GPS hanno forma:
 *   { t, lat, lon, ele, acc, speed, heading }
 * I campioni di moto hanno forma:
 *   { t, ax, ay, az, pitch, roll }
 * dove t è epoch in millisecondi.
 */
'use strict';

class Recorder {
  constructor() {
    this.gps = [];
    this.motion = [];
    this.watchId = null;
    this._motionHandler = null;
    this._orientHandler = null;
    this._wakeLock = null;
    this._lastMotionT = 0;
    this._pitch = null;
    this._roll = null;
    this.onFix = null; // callback(fix) per ogni posizione GPS
    this.onError = null; // callback(message)
    this.running = false;
  }

  /**
   * Su iOS (Safari >= 13) l'accesso ai sensori di moto richiede un consenso
   * esplicito che deve partire da un gesto dell'utente: chiamare questo metodo
   * dentro il gestore del click di avvio.
   */
  static async requestMotionPermission() {
    try {
      if (typeof DeviceMotionEvent !== 'undefined' &&
          typeof DeviceMotionEvent.requestPermission === 'function') {
        await DeviceMotionEvent.requestPermission();
      }
      if (typeof DeviceOrientationEvent !== 'undefined' &&
          typeof DeviceOrientationEvent.requestPermission === 'function') {
        await DeviceOrientationEvent.requestPermission();
      }
    } catch (_) {
      /* consenso negato: l'app funziona comunque, senza dati di moto */
    }
  }

  async start() {
    if (this.running) return;
    this.running = true;
    this.gps = [];
    this.motion = [];

    if (!('geolocation' in navigator)) {
      this.running = false;
      throw new Error('Geolocalizzazione non disponibile su questo dispositivo.');
    }

    this.watchId = navigator.geolocation.watchPosition(
      (pos) => {
        const c = pos.coords;
        const fix = {
          t: pos.timestamp || Date.now(),
          lat: c.latitude,
          lon: c.longitude,
          ele: c.altitude != null ? Math.round(c.altitude * 10) / 10 : null,
          acc: c.accuracy != null ? Math.round(c.accuracy * 10) / 10 : null,
          speed: c.speed != null && !Number.isNaN(c.speed) ? Math.round(c.speed * 100) / 100 : null,
          heading: c.heading != null && !Number.isNaN(c.heading) ? Math.round(c.heading) : null,
        };
        this.gps.push(fix);
        if (this.onFix) this.onFix(fix);
      },
      (err) => {
        if (this.onError) this.onError('Errore GPS: ' + err.message);
      },
      { enableHighAccuracy: true, maximumAge: 0, timeout: 15000 }
    );

    // Orientamento: pitch (beta) e roll (gamma) in gradi.
    this._orientHandler = (e) => {
      if (e.beta != null) this._pitch = Math.round(e.beta * 10) / 10;
      if (e.gamma != null) this._roll = Math.round(e.gamma * 10) / 10;
    };
    window.addEventListener('deviceorientation', this._orientHandler);

    // Accelerometro, sottocampionato a ~10 Hz per contenere la memoria.
    this._motionHandler = (e) => {
      const now = Date.now();
      if (now - this._lastMotionT < 100) return;
      this._lastMotionT = now;
      const a = e.accelerationIncludingGravity || e.acceleration;
      if (!a || a.x == null) return;
      this.motion.push({
        t: now,
        ax: Math.round(a.x * 100) / 100,
        ay: Math.round(a.y * 100) / 100,
        az: Math.round(a.z * 100) / 100,
        pitch: this._pitch,
        roll: this._roll,
      });
    };
    window.addEventListener('devicemotion', this._motionHandler);

    // Wake Lock: evita lo spegnimento dello schermo durante la registrazione.
    try {
      if ('wakeLock' in navigator) {
        this._wakeLock = await navigator.wakeLock.request('screen');
      }
    } catch (_) { /* non bloccante */ }
  }

  stop() {
    if (!this.running) return;
    this.running = false;
    if (this.watchId != null) {
      navigator.geolocation.clearWatch(this.watchId);
      this.watchId = null;
    }
    if (this._motionHandler) {
      window.removeEventListener('devicemotion', this._motionHandler);
      this._motionHandler = null;
    }
    if (this._orientHandler) {
      window.removeEventListener('deviceorientation', this._orientHandler);
      this._orientHandler = null;
    }
    if (this._wakeLock) {
      this._wakeLock.release().catch(() => {});
      this._wakeLock = null;
    }
  }

  lastFix() {
    return this.gps.length ? this.gps[this.gps.length - 1] : null;
  }
}
