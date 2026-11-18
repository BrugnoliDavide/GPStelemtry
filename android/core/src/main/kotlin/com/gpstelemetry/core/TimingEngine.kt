package com.gpstelemetry.core

/**
 * Motore di cronometraggio.
 *
 * Riceve i fix GPS uno alla volta (feed) e, in base al tracciato:
 *  - avvia automaticamente il cronometro all'attraversamento della LINEA di
 *    partenza;
 *  - registra i parziali all'attraversamento delle rispettive linee,
 *    nell'ordine previsto;
 *  - arresta il cronometro all'attraversamento della linea del traguardo;
 *  - contabilizza tempo e numero di uscite dal corridoio di tolleranza.
 *
 * I cancelli sono LINEE RETTE (come le vere linee di partenza/traguardo):
 * il passaggio è l'intersezione tra il segmento formato da due fix GPS
 * consecutivi e la linea del cancello, con istante interpolato linearmente.
 * Il rilevamento è quindi robusto anche ad alta velocità, quando i fix sono
 * molto distanziati.
 *
 * Tracciato circolare: il traguardo (stessa linea della partenza) è armato
 * solo dopo essersi allontanati dalla partenza e dopo un tempo minimo, per
 * evitare arresti immediati.
 *
 * Stati: ARMED -> RUNNING -> FINISHED.
 */
class TimingEngine(
    private val track: Track,
    private val listener: Listener? = null,
    /** Tempo minimo (ms) prima di armare il traguardo su tracciato circolare. */
    private val minLapMs: Long = 10_000,
) {
    interface Listener {
        fun onStart(t: Long) {}
        fun onSplit(split: SplitTime, index: Int) {}
        fun onFinish(t: Long, elapsedMs: Long) {}
        fun onOffTrackEnter(t: Long, episodes: Int) {}
        fun onOffTrackExit(t: Long, totalMs: Long) {}
        /** Ingresso in una neutralizzazione: il cronometro si ferma. */
        fun onNeutralEnter(t: Long, sectorIndex: Int) {}
        /** Uscita dalla neutralizzazione: il cronometro riparte. */
        fun onNeutralExit(t: Long, durationMs: Long) {}
    }

    enum class State { ARMED, RUNNING, FINISHED }

    var state: State = State.ARMED
        private set
    var startTime: Long? = null
        private set
    var finishTime: Long? = null
        private set
    val splitTimes = mutableListOf<SplitTime>()
    var offTrackMs: Long = 0
        private set
    var offTrackEpisodes: Int = 0
        private set
    var isOffTrack: Boolean = false
        private set
    /** Tempo delle neutralizzazioni già concluse (ms). */
    var neutralMs: Long = 0
        private set
    /** Istante di ingresso nella neutralizzazione in corso, se presente. */
    var neutralSince: Long? = null
        private set
    /** Vero mentre si è dentro una neutralizzazione (cronometro fermo). */
    val isNeutral: Boolean get() = neutralSince != null

    private var nextSplit = 0
    private var prevFix: GpsSample? = null
    private var finishArmed = track.type != TrackType.CIRCULAR
    private var farFromStart = false
    private var offSince: Long? = null

    /** Distanza dalla partenza oltre la quale il traguardo circolare si arma. */
    private val armDistance: Double =
        maxOf(track.start.widthM, 2 * maxOf(5.0, track.tolerance))

    /** Tempo di giro EFFETTIVO: le neutralizzazioni non contano (cronometro fermo). */
    fun elapsedMs(now: Long = System.currentTimeMillis()): Long {
        startTime ?: return 0
        return effectiveAt(finishTime ?: now)
    }

    private fun effectiveAt(t: Long): Long {
        val st = startTime ?: return 0
        val open = neutralSince?.let { maxOf(0L, t - it) } ?: 0L
        return maxOf(0L, (t - st) - neutralMs - open)
    }

    /** Chiude la neutralizzazione in corso all'istante [t]; restituisce la durata. */
    private fun closeNeutral(t: Long): Long? {
        val since = neutralSince ?: return null
        val d = maxOf(0L, t - since)
        neutralMs += d
        neutralSince = null
        return d
    }

    /** Chiude un'eventuale uscita dal corridoio ancora aperta. */
    private fun closeOffTrack(t: Long) {
        offSince?.let {
            offTrackMs += maxOf(0L, t - it)
            offSince = null
            isOffTrack = false
        }
    }

    fun feed(fix: GpsSample) {
        val prev = prevFix
        when (state) {
            State.ARMED -> checkStart(prev, fix)
            State.RUNNING -> {
                checkSplits(prev, fix)
                // Nelle neutralizzazioni non si contano le uscite dal tracciato.
                if (!isNeutral) checkOffTrack(prev, fix)
                checkFinish(prev, fix)
            }
            State.FINISHED -> { /* sessione conclusa */ }
        }
        prevFix = fix
    }

    private fun checkStart(prev: GpsSample?, fix: GpsSample) {
        if (prev == null) return
        val t = Geo.gateCrossing(prev, fix, track.start) ?: return
        startTime = t
        state = State.RUNNING
        finishArmed = track.type != TrackType.CIRCULAR
        listener?.onStart(t)
        // Il medesimo segmento può attraversare anche parziali o traguardo.
        checkSplits(prev, fix)
        checkFinish(prev, fix)
    }

    private fun checkSplits(prev: GpsSample?, fix: GpsSample) {
        val st = startTime ?: return
        if (prev == null) return
        // Un singolo segmento può attraversare più parziali consecutivi.
        while (nextSplit < track.splits.size) {
            val sp = track.splits[nextSplit]
            val tCross = Geo.gateCrossing(prev, fix, sp.gate)
                ?.takeIf { it > st && it >= (splitTimes.lastOrNull()?.t ?: st) } ?: return
            // Uscita da una neutralizzazione: il settore appena chiuso è neutro.
            val neutralDur = closeNeutral(tCross)
            val rec = SplitTime(
                name = sp.name.ifBlank { "Parziale ${nextSplit + 1}" },
                t = tCross,
                elapsed = effectiveAt(tCross),
                neutral = neutralDur != null,
                neutralMs = neutralDur ?: 0,
            )
            splitTimes.add(rec)
            val index = nextSplit
            nextSplit++
            listener?.onSplit(rec, splitTimes.size)
            neutralDur?.let { listener?.onNeutralExit(tCross, it) }
            // Ingresso in una neutralizzazione: il cronometro si ferma.
            if (Neutral.isEntry(track.splits, index)) {
                closeOffTrack(tCross)
                neutralSince = tCross
                listener?.onNeutralEnter(tCross, index + 1)
            }
        }
    }

    private fun checkFinish(prev: GpsSample?, fix: GpsSample) {
        val st = startTime ?: return
        if (prev == null) return
        val finish = track.effectiveFinish

        if (track.type == TrackType.CIRCULAR && !finishArmed) {
            val dStart = Geo.haversine(fix.lat, fix.lon, track.start.lat, track.start.lon)
            if (dStart > armDistance) farFromStart = true
            if (farFromStart && fix.t - st > minLapMs) finishArmed = true
            if (!finishArmed) return
        }

        val tCross = Geo.gateCrossing(prev, fix, finish)
            ?.takeIf { it > st } ?: return

        // Traguardo dentro una neutralizzazione (uscita non rilevata): la si
        // chiude qui, così il cronometro non resta fermo per sempre.
        closeNeutral(tCross)
        finishTime = tCross
        closeOffTrack(tCross)
        state = State.FINISHED
        listener?.onFinish(tCross, elapsedMs())
    }

    private fun checkOffTrack(prev: GpsSample?, fix: GpsSample) {
        if (track.path.size < 2) return
        val tol = maxOf(5.0, track.tolerance)
        val out = Geo.distToPath(LatLon(fix.lat, fix.lon), track.path) > tol
        if (out && !isOffTrack) {
            isOffTrack = true
            offSince = fix.t
            offTrackEpisodes++
            listener?.onOffTrackEnter(fix.t, offTrackEpisodes)
        } else if (!out && isOffTrack) {
            offTrackMs += fix.t - (offSince ?: fix.t)
            isOffTrack = false
            offSince = null
            listener?.onOffTrackExit(fix.t, offTrackMs)
        }
    }

    fun summary(now: Long = System.currentTimeMillis()): SessionResult {
        val pendingOff = offSince?.let { now - it } ?: 0
        return SessionResult(
            status = when (state) {
                State.ARMED -> SessionStatus.ARMED
                State.RUNNING -> SessionStatus.RUNNING
                State.FINISHED -> SessionStatus.FINISHED
            },
            startTime = startTime,
            finishTime = finishTime,
            elapsedMs = startTime?.let { elapsedMs(now) },
            splits = splitTimes.toList(),
            offTrackMs = offTrackMs + pendingOff,
            offTrackEpisodes = offTrackEpisodes,
            neutralMs = neutralMs + (neutralSince?.let { maxOf(0L, (finishTime ?: now) - it) } ?: 0L),
        )
    }
}
