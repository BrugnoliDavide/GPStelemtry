package com.gpstelemetry.core

/**
 * Modelli di dominio condivisi tra app telefono e app Wear OS.
 * Tempi in millisecondi epoch, distanze in metri.
 */

enum class TrackType { CIRCULAR, OPEN }

data class LatLon(val lat: Double, val lon: Double)

data class PathPoint(val lat: Double, val lon: Double, val ele: Double? = null)

/**
 * Cancello di cronometraggio: una LINEA RETTA sul terreno (come le vere linee
 * di partenza/traguardo), definita da centro, orientamento e lunghezza.
 * Il tempo scatta quando la traiettoria GPS attraversa la linea.
 */
data class Gate(
    val lat: Double,
    val lon: Double,
    /** Orientamento della linea in gradi (0 = nord-sud, 90 = est-ovest). */
    val headingDeg: Double = 0.0,
    /** Lunghezza totale della linea in metri. */
    val widthM: Double = 30.0,
) {
    val center: LatLon get() = LatLon(lat, lon)
}

/** Parziale (settore): cancello intermedio con nome. */
data class SplitGate(val name: String, val gate: Gate)

data class Track(
    val id: String,
    var name: String,
    var type: TrackType,
    /** Tolleranza in metri: semiampiezza del corridoio attorno al percorso. */
    var tolerance: Double,
    val createdAt: Long,
    var start: Gate,
    /** Nullo per i tracciati circolari (traguardo = partenza). */
    var finish: Gate?,
    var splits: MutableList<SplitGate> = mutableListOf(),
    var path: MutableList<PathPoint> = mutableListOf(),
) {
    val effectiveFinish: Gate get() = if (type == TrackType.CIRCULAR) start else finish ?: start
}

data class GpsSample(
    val t: Long,
    val lat: Double,
    val lon: Double,
    val ele: Double? = null,
    val acc: Float? = null,
    val speed: Float? = null,
    val heading: Float? = null,
)

data class MotionSample(
    val t: Long,
    val ax: Float,
    val ay: Float,
    val az: Float,
    val pitch: Float? = null,
    val roll: Float? = null,
)

/** Campione di frequenza cardiaca ricevuto dallo smartwatch. */
data class HrSample(val t: Long, val bpm: Int)

data class SplitTime(val name: String, val t: Long, val elapsed: Long)

enum class SessionStatus { ARMED, RUNNING, FINISHED, ABORTED }

data class SessionResult(
    val status: SessionStatus,
    val startTime: Long?,
    val finishTime: Long?,
    val elapsedMs: Long?,
    val splits: List<SplitTime>,
    val offTrackMs: Long,
    val offTrackEpisodes: Int,
)

class Session(
    val id: String,
    val trackId: String,
    var name: String,
    val startedAt: Long,
    val trackType: TrackType,
    val trackStart: Gate,
    val trackFinish: Gate?,
    val trackSplits: List<SplitGate>,
    val samples: MutableList<GpsSample> = mutableListOf(),
    val motion: MutableList<MotionSample> = mutableListOf(),
    val hr: MutableList<HrSample> = mutableListOf(),
    var result: SessionResult? = null,
    var status: SessionStatus = SessionStatus.ARMED,
)

/**
 * Riepilogo leggero di una sessione (senza campioni): usato per elenchi,
 * medie e record senza dover leggere tutta la telemetria.
 */
data class SessionSummary(
    val id: String,
    val trackId: String,
    val name: String,
    val startedAt: Long,
    val status: SessionStatus,
    val result: SessionResult?,
)

fun Session.summary() = SessionSummary(id, trackId, name, startedAt, status, result)
