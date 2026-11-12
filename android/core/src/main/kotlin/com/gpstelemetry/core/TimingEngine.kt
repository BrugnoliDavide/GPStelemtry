package com.gpstelemetry.core

/**
 * Motore di cronometraggio (port di `js/timing.js`, stessa semantica e test).
 *
 * Riceve i fix GPS uno alla volta (feed) e, in base al tracciato:
 *  - avvia automaticamente il cronometro all'attraversamento della partenza;
 *  - registra i parziali ai punti intermedi, nell'ordine previsto;
 *  - arresta il cronometro all'attraversamento del traguardo;
 *  - contabilizza tempo e numero di uscite dal corridoio di tolleranza.
 *
 * Il rilevamento dei cancelli usa l'intersezione segmento-cerchio tra fix GPS
 * consecutivi, con interpolazione dell'istante di attraversamento, per non
 * mancare i passaggi ad alta velocità.
 *
 * Tracciato circolare: il traguardo è armato solo dopo essersi allontanati di
 * almeno due raggi dalla partenza e dopo un tempo minimo.
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

    val gateRadius: Double = maxOf(5.0, track.tolerance)

    private var nextSplit = 0
    private var prevFix: GpsSample? = null
    private var wasInsideStart: Boolean? = null
    private var finishArmed = track.type != TrackType.CIRCULAR
    private var farFromStart = false
    private var offSince: Long? = null

    fun elapsedMs(now: Long = System.currentTimeMillis()): Long {
        val st = startTime ?: return 0
        return (finishTime ?: now) - st
    }

    fun feed(fix: GpsSample) {
        val prev = prevFix
        when (state) {
            State.ARMED -> checkStart(prev, fix)
            State.RUNNING -> {
                checkSplits(prev, fix)
                checkOffTrack(prev, fix)
                checkFinish(prev, fix)
            }
            State.FINISHED -> { /* sessione conclusa */ }
        }
        prevFix = fix
    }

    private fun checkStart(prev: GpsSample?, fix: GpsSample) {
        val start = track.start
        val dStart = Geo.haversine(fix.lat, fix.lon, start.lat, start.lon)
        if (wasInsideStart == null) wasInsideStart = dStart <= gateRadius
        if (wasInsideStart == true) {
            // Partenza da fermo dentro la zona: il tempo parte all'uscita.
            if (dStart > gateRadius && prev != null) {
                val g = Geo.segmentGate(prev, fix, start, gateRadius)
                startTime = g?.tExit ?: prev.t
                begin()
            }
        } else if (prev != null) {
            // Arrivo in corsa: il tempo parte all'ingresso nella zona.
            val g = Geo.segmentGate(prev, fix, start, gateRadius)
            if (g != null) {
                startTime = g.tEntry
                begin()
                // il medesimo segmento può attraversare anche parziali/traguardo
                checkSplits(prev, fix)
                checkFinish(prev, fix)
            }
        } else if (dStart <= gateRadius) {
            startTime = fix.t
            begin()
        }
    }

    private fun begin() {
        state = State.RUNNING
        finishArmed = track.type != TrackType.CIRCULAR
        listener?.onStart(startTime ?: 0)
    }

    private fun checkSplits(prev: GpsSample?, fix: GpsSample) {
        val st = startTime ?: return
        // Un singolo segmento può attraversare più parziali consecutivi.
        while (nextSplit < track.splits.size) {
            val sp = track.splits[nextSplit]
            val gate = LatLon(sp.lat, sp.lon)
            val tCross: Long? = if (prev != null) {
                val g = Geo.segmentGate(prev, fix, gate, gateRadius)
                if (g != null && g.tEntry > st) g.tEntry else null
            } else if (Geo.haversine(fix.lat, fix.lon, gate.lat, gate.lon) <= gateRadius) {
                fix.t
            } else null
            if (tCross == null) return
            val rec = SplitTime(
                name = sp.name.ifBlank { "Parziale ${nextSplit + 1}" },
                t = tCross,
                elapsed = tCross - st,
            )
            splitTimes.add(rec)
            nextSplit++
            listener?.onSplit(rec, splitTimes.size)
        }
    }

    private fun checkFinish(prev: GpsSample?, fix: GpsSample) {
        val st = startTime ?: return
        val finish = track.effectiveFinish

        if (track.type == TrackType.CIRCULAR && !finishArmed) {
            val dStart = Geo.haversine(fix.lat, fix.lon, track.start.lat, track.start.lon)
            if (dStart > gateRadius * 2) farFromStart = true
            if (farFromStart && fix.t - st > minLapMs) finishArmed = true
            if (!finishArmed) return
        }

        val tCross: Long? = if (prev != null) {
            val g = Geo.segmentGate(prev, fix, finish, gateRadius)
            if (g != null && g.tEntry > st) g.tEntry else null
        } else if (Geo.haversine(fix.lat, fix.lon, finish.lat, finish.lon) <= gateRadius) {
            fix.t
        } else null

        if (tCross != null) {
            finishTime = tCross
            // chiusura di un'eventuale uscita dal corridoio ancora aperta
            offSince?.let {
                offTrackMs += tCross - it
                offSince = null
                isOffTrack = false
            }
            state = State.FINISHED
            listener?.onFinish(tCross, elapsedMs())
        }
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
        )
    }
}
