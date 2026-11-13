package com.gpstelemetry.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Resoconto di un giro (o dell'intera sessione se il giro non è concluso).
 *
 *  - Dislivello positivo (D+) e negativo (D−) dalla quota GPS, con isteresi
 *    [ELEV_THRESHOLD_M] per non sommare il rumore della quota.
 *  - Velocità massima (dal GPS, scartando i fix poco precisi) e media
 *    (distanza / tempo).
 *  - Accelerazione massima in g dal GPS: combinazione della longitudinale
 *    (Δv/Δt) e della laterale in curva (v·Δrotta/Δt), mediata su fix
 *    consecutivi. Indipendente da come è montato il telefono.
 *  - Inclinazione massima: scostamento di pitch/roll dalla posizione di
 *    riposo del telefono (mediana dei campioni prima della partenza): su una
 *    moto corrisponde all'angolo di piega.
 *  - Uscite dal tracciato: numero e tempo fuori tolleranza.
 */
data class LapStats(
    val distanceM: Double,
    val durationMs: Long,
    val maxSpeedKmh: Double?,
    val avgSpeedKmh: Double?,
    val maxAccelG: Double?,
    val elevGainM: Double?,
    val elevLossM: Double?,
    val maxInclineDeg: Double?,
    val avgHr: Int?,
    val offTrackEpisodes: Int,
    val offTrackMs: Long,
) {
    companion object {
        /** Variazione minima di quota conteggiata (m). */
        const val ELEV_THRESHOLD_M = 3.0
        /** Fix con precisione peggiore di così non entrano nei massimi. */
        private const val MAX_ACC_M = 25f
        /** Accelerazioni oltre questa soglia sono errori del GPS. */
        private const val MAX_PLAUSIBLE_G = 4.0
        private const val G = 9.80665

        fun compute(s: Session): LapStats {
            val r = s.result
            val st = r?.startTime
            val ft = r?.finishTime
            val inLap: (Long) -> Boolean =
                if (st != null && ft != null) { t -> t in st..ft } else { _ -> true }

            val gps = s.samples.filter { inLap(it.t) }.sortedBy { it.t }
            val distance = gps.zipWithNext { a, b -> Geo.haversine(a.lat, a.lon, b.lat, b.lon) }.sum()
            val duration = when {
                r?.elapsedMs != null && st != null && ft != null -> r.elapsedMs
                gps.size > 1 -> gps.last().t - gps.first().t
                else -> 0L
            }

            val good = gps.filter { (it.acc ?: 0f) <= MAX_ACC_M }
            val maxSpeed = good.mapNotNull { it.speed }.maxOrNull()?.let { it * 3.6 }
                ?: speedsFromPositions(good).maxOrNull()?.let { it * 3.6 }
            val avgSpeed = if (duration > 0 && distance > 0) distance / (duration / 1000.0) * 3.6 else null

            val (gain, loss) = elevation(gps)
            val hr = s.hr.filter { inLap(it.t) }.map { it.bpm }

            return LapStats(
                distanceM = distance,
                durationMs = duration,
                maxSpeedKmh = maxSpeed,
                avgSpeedKmh = avgSpeed,
                maxAccelG = maxAccel(good),
                elevGainM = gain,
                elevLossM = loss,
                maxInclineDeg = maxIncline(s.motion, st, inLap),
                avgHr = hr.takeIf { it.isNotEmpty() }?.average()?.toInt(),
                offTrackEpisodes = r?.offTrackEpisodes ?: 0,
                offTrackMs = r?.offTrackMs ?: 0,
            )
        }

        /** D+ e D− con isteresi; null se la quota non è disponibile. */
        fun elevation(gps: List<GpsSample>): Pair<Double?, Double?> {
            val eles = gps.mapNotNull { it.ele }
            if (eles.size < 2) return null to null
            var anchor = eles.first()
            var gain = 0.0
            var loss = 0.0
            for (e in eles.drop(1)) {
                val d = e - anchor
                if (d >= ELEV_THRESHOLD_M) {
                    gain += d
                    anchor = e
                } else if (d <= -ELEV_THRESHOLD_M) {
                    loss += -d
                    anchor = e
                }
            }
            return gain to loss
        }

        /** Velocità (m/s) ricavate dalle posizioni, se il GPS non le fornisce. */
        private fun speedsFromPositions(gps: List<GpsSample>): List<Double> =
            gps.zipWithNext { a, b ->
                val dt = (b.t - a.t) / 1000.0
                if (dt <= 0) null else Geo.haversine(a.lat, a.lon, b.lat, b.lon) / dt
            }.filterNotNull()

        /** Massima accelerazione combinata (g), mediata su 3 fix. */
        fun maxAccel(gps: List<GpsSample>): Double? {
            if (gps.size < 3) return null
            // Velocità e rotta per ogni fix (dal GPS o dalle posizioni).
            val v = DoubleArray(gps.size)
            val h = DoubleArray(gps.size)
            for (i in gps.indices) {
                val p = gps[maxOf(0, i - 1)]
                val n = gps[minOf(gps.size - 1, i + 1)]
                val dt = (n.t - p.t) / 1000.0
                v[i] = gps[i].speed?.toDouble()
                    ?: if (dt > 0) Geo.haversine(p.lat, p.lon, n.lat, n.lon) / dt else 0.0
                h[i] = gps[i].heading?.toDouble()
                    ?: Geo.bearingDeg(LatLon(p.lat, p.lon), LatLon(n.lat, n.lon))
            }
            val accel = mutableListOf<Double>()
            for (i in 1 until gps.size) {
                val dt = (gps[i].t - gps[i - 1].t) / 1000.0
                if (dt <= 0.05 || dt > 3.0) continue
                val along = (v[i] - v[i - 1]) / dt
                var dh = h[i] - h[i - 1]
                while (dh > 180) dh -= 360
                while (dh < -180) dh += 360
                // Sotto i 2 m/s la rotta GPS è inaffidabile.
                val lateral = if (v[i] > 2.0) (v[i] + v[i - 1]) / 2 * (dh * PI / 180) / dt else 0.0
                accel.add(hypot(along, lateral) / G)
            }
            if (accel.isEmpty()) return null
            val smooth = accel.indices.map { i ->
                val w = accel.subList(maxOf(0, i - 1), minOf(accel.size, i + 2))
                w.average()
            }
            return smooth.filter { it < MAX_PLAUSIBLE_G }.maxOrNull()
        }

        /**
         * Inclinazione massima (°) rispetto alla posizione di riposo del
         * telefono: mediana di pitch/roll dei campioni prima della partenza
         * (o dei primi campioni della sessione).
         */
        fun maxIncline(motion: List<MotionSample>, startTime: Long?, inLap: (Long) -> Boolean): Double? {
            val oriented = motion.filter { it.pitch != null && it.roll != null }
            if (oriented.size < 5) return null
            val rest = oriented.filter { startTime != null && it.t < startTime }.takeLast(50)
                .ifEmpty { oriented.take(20) }
            val p0 = median(rest.map { it.pitch!!.toDouble() })
            val r0 = median(rest.map { it.roll!!.toDouble() })
            val values = oriented.filter { inLap(it.t) }.map {
                max(abs(angleDiff(it.pitch!!.toDouble(), p0)), abs(angleDiff(it.roll!!.toDouble(), r0)))
            }
            if (values.isEmpty()) return null
            // Media mobile su 3 campioni (~0,3 s) contro i picchi delle vibrazioni.
            return values.indices.maxOf { i ->
                values.subList(maxOf(0, i - 1), minOf(values.size, i + 2)).average()
            }
        }

        private fun median(x: List<Double>): Double {
            val s = x.sorted()
            return if (s.isEmpty()) 0.0 else s[s.size / 2]
        }

        private fun angleDiff(a: Double, b: Double): Double {
            var d = a - b
            while (d > 180) d -= 360
            while (d < -180) d += 360
            return d
        }
    }
}
