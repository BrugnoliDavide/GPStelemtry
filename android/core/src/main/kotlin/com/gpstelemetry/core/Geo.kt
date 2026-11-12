package com.gpstelemetry.core

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Funzioni geodetiche e geometriche. Distanze in metri, tempi in millisecondi.
 * Port del modulo `js/geo.js` (stessa semantica, stessi test).
 */
object Geo {
    private const val R = 6371008.8 // raggio medio terrestre (m)
    private fun rad(deg: Double) = deg * PI / 180.0

    interface HasLatLon {
        val lat: Double
        val lon: Double
    }

    fun haversine(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val dLat = rad(bLat - aLat)
        val dLon = rad(bLon - aLon)
        val s = sin(dLat / 2) * sin(dLat / 2) +
            cos(rad(aLat)) * cos(rad(bLat)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * R * asin(min(1.0, sqrt(s)))
    }

    fun haversine(a: LatLon, b: LatLon) = haversine(a.lat, a.lon, b.lat, b.lon)

    /** Proiezione equirettangolare locale attorno a ref: {x, y} in metri. */
    fun toXY(pLat: Double, pLon: Double, refLat: Double, refLon: Double): Pair<Double, Double> {
        val x = rad(pLon - refLon) * R * cos(rad(refLat))
        val y = rad(pLat - refLat) * R
        return x to y
    }

    /** Distanza punto-segmento in metri. */
    fun distToSegment(p: LatLon, a: LatLon, b: LatLon): Double {
        val (px, py) = toXY(p.lat, p.lon, a.lat, a.lon)
        val (bx, by) = toXY(b.lat, b.lon, a.lat, a.lon)
        val len2 = bx * bx + by * by
        if (len2 == 0.0) return hypot(px, py)
        var t = (px * bx + py * by) / len2
        t = t.coerceIn(0.0, 1.0)
        return hypot(px - t * bx, py - t * by)
    }

    /** Distanza minima dal punto alla polilinea (corridoio di tolleranza). */
    fun distToPath(p: LatLon, path: List<PathPoint>): Double {
        if (path.isEmpty()) return Double.POSITIVE_INFINITY
        if (path.size == 1) return haversine(p.lat, p.lon, path[0].lat, path[0].lon)
        var best = Double.POSITIVE_INFINITY
        for (i in 0 until path.size - 1) {
            val a = LatLon(path[i].lat, path[i].lon)
            val b = LatLon(path[i + 1].lat, path[i + 1].lon)
            // pre-filtro grossolano
            val dv = haversine(p, a)
            val segLen = haversine(a, b)
            if (dv - segLen > best) continue
            val d = distToSegment(p, a, b)
            if (d < best) best = d
        }
        return best
    }

    fun pathLength(path: List<PathPoint>): Double {
        var l = 0.0
        for (i in 1 until path.size) {
            l += haversine(path[i - 1].lat, path[i - 1].lon, path[i].lat, path[i].lon)
        }
        return l
    }

    data class GateCrossing(
        /** Timestamp interpolato di ingresso nella zona del cancello. */
        val tEntry: Long,
        /** Timestamp interpolato di uscita dalla zona del cancello. */
        val tExit: Long,
        val minDist: Double,
        val startInside: Boolean,
        val endInside: Boolean,
    )

    /**
     * Intersezione tra il segmento prev→cur (fix GPS consecutivi) e la zona
     * circolare di un cancello. Necessaria perché ad alta velocità due fix
     * consecutivi possono "scavalcare" il cancello senza campioni interni.
     * Restituisce null se il segmento non entra nella zona.
     */
    fun segmentGate(prev: GpsSample, cur: GpsSample, gate: LatLon, radius: Double): GateCrossing? {
        val (p0x, p0y) = toXY(prev.lat, prev.lon, gate.lat, gate.lon)
        val (p1x, p1y) = toXY(cur.lat, cur.lon, gate.lat, gate.lon)
        val dx = p1x - p0x
        val dy = p1y - p0y
        val a = dx * dx + dy * dy
        val b = 2 * (p0x * dx + p0y * dy)
        val c = p0x * p0x + p0y * p0y - radius * radius
        fun distAt(t: Double) = hypot(p0x + t * dx, p0y + t * dy)
        val tMin = if (a > 0) (-b / (2 * a)).coerceIn(0.0, 1.0) else 0.0
        if (distAt(tMin) > radius) return null
        var tEntry = 0.0
        var tExit = 1.0
        val disc = b * b - 4 * a * c
        if (a > 0 && disc >= 0) {
            val sq = sqrt(disc)
            tEntry = ((-b - sq) / (2 * a)).coerceIn(0.0, 1.0)
            tExit = ((-b + sq) / (2 * a)).coerceIn(0.0, 1.0)
        }
        return GateCrossing(
            tEntry = prev.t + (tEntry * (cur.t - prev.t)).toLong(),
            tExit = prev.t + (tExit * (cur.t - prev.t)).toLong(),
            minDist = distAt(tMin),
            startInside = c <= 0,
            endInside = distAt(1.0) <= radius,
        )
    }

    /** Semplificazione di Douglas-Peucker (tolleranza in metri). */
    fun simplify(path: List<PathPoint>, tolerance: Double): List<PathPoint> {
        if (path.size <= 2) return path.toList()
        val keep = BooleanArray(path.size)
        keep[0] = true
        keep[path.size - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to path.size - 1)
        while (stack.isNotEmpty()) {
            val (i0, i1) = stack.removeLast()
            var maxD = -1.0
            var idx = -1
            val a = LatLon(path[i0].lat, path[i0].lon)
            val b = LatLon(path[i1].lat, path[i1].lon)
            for (i in i0 + 1 until i1) {
                val d = distToSegment(LatLon(path[i].lat, path[i].lon), a, b)
                if (d > maxD) {
                    maxD = d
                    idx = i
                }
            }
            if (maxD > tolerance && idx > 0) {
                keep[idx] = true
                stack.addLast(i0 to idx)
                stack.addLast(idx to i1)
            }
        }
        return path.filterIndexed { i, _ -> keep[i] }
    }
}
