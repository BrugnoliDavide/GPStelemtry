/*
 * timing.js — Motore di cronometraggio.
 *
 * Riceve i fix GPS uno alla volta (feed) e, in base al tracciato selezionato:
 *  - avvia automaticamente il cronometro all'attraversamento della partenza;
 *  - registra i tempi parziali ai punti intermedi definiti, nell'ordine previsto;
 *  - arresta il cronometro all'attraversamento del traguardo;
 *  - contabilizza il tempo trascorso fuori dal margine di tolleranza
 *    (corridoio attorno al percorso registrato) e il numero di uscite.
 *
 * Rilevamento dei "cancelli" (partenza, parziali, traguardo): un cancello è una
 * zona circolare di raggio pari alla tolleranza del tracciato. L'istante di
 * attraversamento è interpolato linearmente tra i due fix a cavallo della
 * soglia, per una precisione superiore alla frequenza del GPS.
 *
 * Tracciato circolare (partenza == traguardo): il rilevamento del traguardo è
 * armato solo dopo che ci si è allontanati dalla partenza di almeno due volte
 * il raggio del cancello e dopo un tempo minimo, per evitare arresti immediati.
 *
 * Stati: 'armed' -> 'running' -> 'finished'.
 */
'use strict';

class TimingEngine {
  /**
   * @param {object} track tracciato selezionato
   * @param {function} onEvent callback({type, ...}) per gli eventi di gara
   */
  constructor(track, onEvent) {
    this.track = track;
    this.onEvent = onEvent || (() => {});
    this.state = 'armed';
    this.gateRadius = Math.max(5, Number(track.tolerance) || 10);
    this.startTime = null;
    this.finishTime = null;
    this.splitTimes = []; // [{name, t, elapsed}]
    this._nextSplit = 0;
    this._prevFix = null;
    this._wasInsideStart = null; // true se i primi fix sono già dentro la zona di partenza
    this._finishArmed = false;
    this._minLapMs = 10000; // tempo minimo prima di armare il traguardo su circuito
    this._farFromStart = false;
    this.offTrackMs = 0;
    this.offTrackEpisodes = 0;
    this._isOff = false;
    this._offSince = null;
  }

  get elapsed() {
    if (this.startTime == null) return 0;
    const end = this.finishTime != null ? this.finishTime : Date.now();
    return end - this.startTime;
  }

  /** Processa un fix GPS {t, lat, lon, ...}. */
  feed(fix) {
    const t = this.track;
    const prev = this._prevFix;

    if (this.state === 'armed') {
      const dStart = Geo.haversine(fix, t.start);
      if (this._wasInsideStart === null) {
        this._wasInsideStart = dStart <= this.gateRadius;
      }
      if (this._wasInsideStart) {
        // Partenza da fermo dentro la zona: il tempo parte all'uscita.
        if (dStart > this.gateRadius && prev) {
          const g = Geo.segmentGate(prev, fix, t.start, this.gateRadius);
          this.startTime = g ? g.tExit : prev.t;
          this._begin();
        }
      } else if (prev) {
        // Arrivo in corsa: il tempo parte all'ingresso nella zona. Il
        // rilevamento segmento-cerchio copre anche i passaggi tra due fix
        // consecutivi senza campioni dentro il raggio (alta velocità).
        const g = Geo.segmentGate(prev, fix, t.start, this.gateRadius);
        if (g) {
          this.startTime = g.tEntry;
          this._begin();
          // se il cancello è stato attraversato completamente nel segmento,
          // valuta subito parziali/traguardo sul medesimo segmento
          this._checkSplits(prev, fix);
          this._checkFinish(prev, fix);
        }
      } else if (dStart <= this.gateRadius) {
        this.startTime = fix.t;
        this._begin();
      }
    } else if (this.state === 'running') {
      this._checkSplits(prev, fix);
      this._checkOffTrack(prev, fix);
      this._checkFinish(prev, fix);
    }

    this._prevFix = fix;
  }

  _begin() {
    this.state = 'running';
    this._finishArmed = this.track.type !== 'circular';
    this.onEvent({ type: 'start', t: this.startTime });
  }

  _checkSplits(prev, fix) {
    // Un singolo segmento può attraversare più parziali consecutivi.
    while (this._nextSplit < (this.track.splits || []).length) {
      const sp = this.track.splits[this._nextSplit];
      let tCross = null;
      if (prev) {
        const g = Geo.segmentGate(prev, fix, sp, this.gateRadius);
        if (g && g.tEntry > this.startTime) tCross = g.tEntry;
      } else if (Geo.haversine(fix, sp) <= this.gateRadius) {
        tCross = fix.t;
      }
      if (tCross == null) return;
      const rec = {
        name: sp.name || `Parziale ${this._nextSplit + 1}`,
        t: tCross,
        elapsed: tCross - this.startTime,
      };
      this.splitTimes.push(rec);
      this._nextSplit++;
      this.onEvent({ type: 'split', ...rec, index: this.splitTimes.length });
    }
  }

  _checkFinish(prev, fix) {
    const t = this.track;
    const finish = t.type === 'circular' ? t.start : t.finish;
    if (!finish) return;

    if (t.type === 'circular' && !this._finishArmed) {
      const dStart = Geo.haversine(fix, t.start);
      if (dStart > this.gateRadius * 2) this._farFromStart = true;
      if (this._farFromStart && fix.t - this.startTime > this._minLapMs) {
        this._finishArmed = true;
      }
      if (!this._finishArmed) return;
    }

    let tCross = null;
    if (prev) {
      const g = Geo.segmentGate(prev, fix, finish, this.gateRadius);
      if (g && g.tEntry > this.startTime) tCross = g.tEntry;
    } else if (Geo.haversine(fix, finish) <= this.gateRadius) {
      tCross = fix.t;
    }
    if (tCross != null) {
      this.finishTime = tCross;
      // chiusura di un'eventuale uscita dal tracciato ancora aperta
      if (this._isOff && this._offSince != null) {
        this.offTrackMs += this.finishTime - this._offSince;
        this._isOff = false;
        this._offSince = null;
      }
      this.state = 'finished';
      this.onEvent({ type: 'finish', t: this.finishTime, elapsed: this.elapsed });
    }
  }

  _checkOffTrack(prev, fix) {
    const path = this.track.path;
    if (!path || path.length < 2) return;
    const tol = Math.max(5, Number(this.track.tolerance) || 10);
    const out = Geo.distToPath(fix, path) > tol;
    if (out && !this._isOff) {
      this._isOff = true;
      this._offSince = fix.t;
      this.offTrackEpisodes++;
      this.onEvent({ type: 'offtrack-enter', t: fix.t, episodes: this.offTrackEpisodes });
    } else if (!out && this._isOff) {
      this.offTrackMs += fix.t - (this._offSince != null ? this._offSince : fix.t);
      this._isOff = false;
      this._offSince = null;
      this.onEvent({ type: 'offtrack-exit', t: fix.t, totalMs: this.offTrackMs });
    }
  }

  /** Riepilogo finale della sessione. */
  summary() {
    return {
      state: this.state,
      startTime: this.startTime,
      finishTime: this.finishTime,
      elapsedMs: this.startTime != null ? this.elapsed : null,
      splits: this.splitTimes,
      offTrackMs: this.offTrackMs + (this._isOff && this._offSince != null ? Date.now() - this._offSince : 0),
      offTrackEpisodes: this.offTrackEpisodes,
    };
  }
}
