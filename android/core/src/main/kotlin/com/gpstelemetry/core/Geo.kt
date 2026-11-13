package com.gpstelemetry.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
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

    /**
     * Sposta un punto di dxEast metri verso est e dyNorth metri verso nord.
     */
    fun offset(p: LatLon, dxEast: Double, dyNorth: Double): LatLon {
        val dLat = dyNorth / R * 180.0 / PI
        val dLon = dxEast / (R * cos(rad(p.lat))) * 180.0 / PI
        return LatLon(p.lat + dLat, p.lon + dLon)
    }

    /** Rotta iniziale approssimata da a verso b, in gradi [0, 360). */
    fun bearingDeg(a: LatLon, b: LatLon): Double {
        val (x, y) = toXY(b.lat, b.lon, a.lat, a.lon)
        return (atan2(x, y) * 180.0 / PI + 360.0) % 360.0
    }

    /** I due estremi della linea di un cancello. */
    fun gateEndpoints(gate: Gate): Pair<LatLon, LatLon> {
        val h = rad(gate.headingDeg)
        val dx = sin(h) * gate.widthM / 2
        val dy = cos(h) * gate.widthM / 2
        return offset(gate.center, dx, dy) to offset(gate.center, -dx, -dy)
    }

    /**
     * Attraversamento della LINEA di un cancello da parte del segmento tra due
     * fix GPS consecutivi (intersezione segmento-segmento in proiezione locale).
     * Intrinsecamente robusto ad alta velocità: il passaggio è rilevato anche
     * senza alcun campione vicino alla linea. Un segmento parallelo alla linea
     * non la attraversa e restituisce null.
     * @return timestamp interpolato dell'attraversamento, oppure null.
     */
    fun gateCrossing(prev: GpsSample, cur: GpsSample, gate: Gate): Long? {
        val (p0x, p0y) = toXY(prev.lat, prev.lon, gate.lat, gate.lon)
        val (p1x, p1y) = toXY(cur.lat, cur.lon, gate.lat, gate.lon)
        val h = rad(gate.headingDeg)
        val gx = sin(h) * gate.widthM / 2
        val gy = cos(h) * gate.widthM / 2
        // Segmento GPS: P = p0 + s·r; linea cancello: Q = (-gx,-gy) + u·(2gx,2gy).
        val rx = p1x - p0x
        val ry = p1y - p0y
        val sx = 2 * gx
        val sy = 2 * gy
        val denom = rx * sy - ry * sx
        if (abs(denom) < 1e-12) return null // parallelo o degenere
        val qx = -gx - p0x
        val qy = -gy - p0y
        val s = (qx * sy - qy * sx) / denom
        val u = (qx * ry - qy * rx) / denom
        if (s < 0.0 || s > 1.0 || u < 0.0 || u > 1.0) return null
        return prev.t + (s * (cur.t - prev.t)).toLong()
    }

    /**
     * Direzione (rotta in gradi) del percorso nel punto più vicino a p.
     * Utile per orientare automaticamente i cancelli perpendicolari al percorso.
     */
    fun pathHeadingAt(path: List<PathPoint>, p: LatLon): Double? {
        if (path.size < 2) return null
        var best = Double.MAX_VALUE
        var bearing: Double? = null
        for (i in 0 until path.size - 1) {
            val a = LatLon(path[i].lat, path[i].lon)
            val b = LatLon(path[i + 1].lat, path[i + 1].lon)
            val d = distToSegment(p, a, b)
            if (d < best) {
                best = d
                bearing = bearingDeg(a, b)
            }
        }
        return bearing
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
