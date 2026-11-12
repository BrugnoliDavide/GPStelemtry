package com.gpstelemetry.core

/**
 * Modelli di dominio condivisi tra app telefono e app Wear OS.
 * Tempi in millisecondi epoch, distanze in metri.
 */

enum class TrackType { CIRCULAR, OPEN }

data class LatLon(val lat: Double, val lon: Double)

data class PathPoint(val lat: Double, val lon: Double, val ele: Double? = null)

data class SplitPoint(val name: String, val lat: Double, val lon: Double)

data class Track(
    val id: String,
    var name: String,
    var type: TrackType,
    /** Tolleranza in metri: raggio dei cancelli e semiampiezza del corridoio. */
    var tolerance: Double,
    val createdAt: Long,
    var start: LatLon,
    /** Nullo per i tracciati circolari (traguardo = partenza). */
    var finish: LatLon?,
    var splits: MutableList<SplitPoint> = mutableListOf(),
    var path: MutableList<PathPoint> = mutableListOf(),
) {
    val effectiveFinish: LatLon get() = if (type == TrackType.CIRCULAR) start else finish ?: start
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
    val trackStart: LatLon,
    val trackFinish: LatLon?,
    val trackSplits: List<SplitPoint>,
    val samples: MutableList<GpsSample> = mutableListOf(),
    val motion: MutableList<MotionSample> = mutableListOf(),
    val hr: MutableList<HrSample> = mutableListOf(),
    var result: SessionResult? = null,
    var status: SessionStatus = SessionStatus.ARMED,
)
