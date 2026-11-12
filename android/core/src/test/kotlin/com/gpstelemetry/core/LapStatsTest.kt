package com.gpstelemetry.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LapStatsTest {
    private val lat0 = 45.0
    private val mLon = 111_320.0 * cos(lat0 * PI / 180)
    private fun at(x: Double, y: Double = 0.0) = LatLon(lat0 + y / 111_320.0, 9.0 + x / mLon)

    private fun session(samples: List<GpsSample>, motion: List<MotionSample> = emptyList(), start: Long, end: Long, off: Int = 0): Session {
        val s = Session("s", "t", "n", 0, TrackType.OPEN, Gate(0.0, 0.0), null, emptyList())
        s.samples.addAll(samples)
        s.motion.addAll(motion)
        s.status = SessionStatus.FINISHED
        s.result = SessionResult(SessionStatus.FINISHED, start, end, end - start, emptyList(), off * 1500L, off)
        return s
    }

    @Test
    fun speedDistanceAndElevation() {
        // Rettilineo a 20 m/s per 50 s, salita di 30 m poi discesa di 12 m, con rumore di ±1 m.
        val samples = (0..50).map { i ->
            val ele = (if (i <= 30) 100.0 + i else 130.0 - (i - 30) * 0.6) + if (i % 2 == 0) 1.0 else -1.0
            val p = at(i * 20.0)
            GpsSample(i * 1000L, p.lat, p.lon, ele = ele, acc = 4f, speed = 20f, heading = 90f)
        }
        val st = LapStats.compute(session(samples, start = 0, end = 50_000))
        assertEquals(1000.0, st.distanceM, 2.0)
        assertEquals(72.0, st.maxSpeedKmh!!, 0.01)
        assertEquals(72.0, st.avgSpeedKmh!!, 0.5)
        assertTrue(st.elevGainM!! in 26.0..34.0, "D+ ${st.elevGainM}")
        assertTrue(st.elevLossM!! in 8.0..16.0, "D- ${st.elevLossM}")
        // Velocità costante in rettilineo: nessuna accelerazione significativa.
        assertTrue(st.maxAccelG!! < 0.05, "g ${st.maxAccelG}")
    }

    @Test
    fun corneringAndBrakingAcceleration() {
        // Curva a raggio 50 m a 20 m/s → laterale v²/r = 8 m/s² ≈ 0,82 g.
        val r = 50.0
        val v = 20.0
        val samples = (0..20).map { i ->
            val ang = v / r * i * 0.5 // 0,5 s tra i fix
            val p = at(r * sin(ang), r - r * cos(ang))
            val heading = (90.0 - ang * 180 / PI + 360) % 360
            GpsSample(i * 500L, p.lat, p.lon, acc = 3f, speed = v.toFloat(), heading = heading.toFloat())
        }
        val st = LapStats.compute(session(samples, start = 0, end = 10_000))
        assertEquals(0.82, st.maxAccelG!!, 0.06)

        // Frenata da 30 a 10 m/s in 2 s → 10 m/s² ≈ 1,02 g.
        val brake = (0..10).map { i ->
            val speed = if (i < 4) 30.0 else if (i <= 8) 30.0 - (i - 4) * 5.0 else 10.0
            val p = at(i * 10.0)
            GpsSample(i * 500L, p.lat, p.lon, acc = 3f, speed = speed.toFloat(), heading = 90f)
        }
        assertEquals(1.02, LapStats.compute(session(brake, start = 0, end = 5_000)).maxAccelG!!, 0.08)
    }

    @Test
    fun inclineFromRestPosition() {
        // Telefono montato con pitch 10° e roll -5°; in pista piega di 40° sul roll.
        val motion = (0 until 40).map { i ->
            val t = i * 100L
            val roll = if (t < 1000) -5f else -5f + 40f * sin(PI * (t - 1000) / 3000).toFloat().coerceAtLeast(0f)
            MotionSample(t, 0f, 0f, 9.8f, pitch = 10f, roll = roll)
        }
        val gps = listOf(GpsSample(0, 45.0, 9.0), GpsSample(4000, 45.0, 9.001))
        val st = LapStats.compute(session(gps, motion, start = 1000, end = 4000))
        assertEquals(40.0, st.maxInclineDeg!!, 1.5)
    }

    @Test
    fun offTrackAndMissingData() {
        val gps = listOf(GpsSample(0, 45.0, 9.0), GpsSample(1000, 45.0, 9.0001))
        val st = LapStats.compute(session(gps, start = 0, end = 1000, off = 3))
        assertEquals(3, st.offTrackEpisodes)
        assertEquals(4500L, st.offTrackMs)
        assertNull(st.elevGainM)
        assertNull(st.maxInclineDeg)
        assertNull(st.avgHr)
    }
}
