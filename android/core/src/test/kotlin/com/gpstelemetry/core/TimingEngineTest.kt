package com.gpstelemetry.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Test del motore di cronometraggio con dati GPS sintetici.
 * Stessi scenari dei test della versione web (tests/test-engine.js).
 */
class TimingEngineTest {
    private val lat0 = 45.0
    private val mPerDegLon = 111_320.0 * cos(lat0 * PI / 180)
    private val t0 = 1_700_000_000_000L

    private fun pt(xMeters: Double, yMeters: Double = 0.0) =
        LatLon(lat0 + yMeters / 111_320.0, 9.0 + xMeters / mPerDegLon)

    private fun fix(xMeters: Double, yMeters: Double, t: Long): GpsSample {
        val p = pt(xMeters, yMeters)
        return GpsSample(t = t, lat = p.lat, lon = p.lon)
    }

    private fun straightPath(): MutableList<PathPoint> {
        val path = mutableListOf<PathPoint>()
        var x = 0.0
        while (x <= 1000.0) {
            val p = pt(x)
            path.add(PathPoint(p.lat, p.lon))
            x += 50.0
        }
        return path
    }

    private class Events : TimingEngine.Listener {
        val types = mutableListOf<String>()
        override fun onStart(t: Long) { types.add("start") }
        override fun onSplit(split: SplitTime, index: Int) { types.add("split") }
        override fun onFinish(t: Long, elapsedMs: Long) { types.add("finish") }
        override fun onOffTrackEnter(t: Long, episodes: Int) { types.add("off-enter") }
        override fun onOffTrackExit(t: Long, totalMs: Long) { types.add("off-exit") }
    }

    @Test
    fun geodesia() {
        val d = Geo.haversine(pt(0.0), pt(1000.0))
        assertTrue(abs(d - 1000.0) < 2.0, "haversine ~1000 m (ottenuto $d)")
        val seg = Geo.distToSegment(pt(500.0, 30.0), pt(0.0), pt(1000.0))
        assertTrue(abs(seg - 30.0) < 1.0, "distToSegment ~30 m (ottenuto $seg)")
    }

    @Test
    fun tracciatoApertoConParzialeEFuoriTolleranza() {
        val sp = pt(500.0)
        val track = Track(
            id = "t1", name = "Aperto", type = TrackType.OPEN, tolerance = 10.0,
            createdAt = 0, start = pt(0.0), finish = pt(1000.0),
            splits = mutableListOf(SplitPoint("P1", sp.lat, sp.lon)),
            path = straightPath(),
        )
        val ev = Events()
        val eng = TimingEngine(track, ev)
        // 10 m/s da x=-100 a x=1100, fix ogni secondo; deviazione di 30 m tra x=700 e x=800.
        for (i in 0..120) {
            val x = -100.0 + i * 10.0
            val y = if (x in 700.0..800.0) 30.0 else 0.0
            eng.feed(fix(x, y, t0 + i * 1000L))
            if (eng.state == TimingEngine.State.FINISHED) break
        }
        val s = eng.summary(t0)
        assertTrue("start" in ev.types, "partenza emessa")
        assertTrue("split" in ev.types, "parziale rilevato")
        assertTrue("finish" in ev.types, "traguardo emesso")
        assertTrue("off-enter" in ev.types && "off-exit" in ev.types, "uscita/rientro corridoio")
        assertEquals(1, s.offTrackEpisodes, "una sola uscita")
        assertTrue(s.offTrackMs in 8000..12000, "fuori tolleranza ~10 s (${s.offTrackMs} ms)")
        // Ingresso nel raggio a x=-10 e a x=990: 1000 m a 10 m/s = 100 s.
        assertNotNull(s.elapsedMs)
        assertTrue(abs(s.elapsedMs!! - 100_000) < 1500, "tempo ~100 s (${s.elapsedMs} ms)")
        assertTrue(abs(s.splits[0].elapsed - 50_000) < 1500, "parziale ~50 s (${s.splits[0].elapsed} ms)")
    }

    @Test
    fun tracciatoCircolareConPartenzaDaFermo() {
        // Quadrato 400x400 m, partenza nell'angolo (0,0).
        val sq = mutableListOf<PathPoint>()
        var x = 0.0
        while (x <= 400) { val p = pt(x, 0.0); sq.add(PathPoint(p.lat, p.lon)); x += 40 }
        var y = 40.0
        while (y <= 400) { val p = pt(400.0, y); sq.add(PathPoint(p.lat, p.lon)); y += 40 }
        x = 360.0
        while (x >= 0) { val p = pt(x, 400.0); sq.add(PathPoint(p.lat, p.lon)); x -= 40 }
        y = 360.0
        while (y >= 0) { val p = pt(0.0, y); sq.add(PathPoint(p.lat, p.lon)); y -= 40 }

        val track = Track(
            id = "t2", name = "Circuito", type = TrackType.CIRCULAR, tolerance = 12.0,
            createdAt = 0, start = pt(0.0, 0.0), finish = null, path = sq,
        )
        val ev = Events()
        val eng = TimingEngine(track, ev)
        // Giro del perimetro a 8 m/s partendo da dentro la zona di partenza.
        val perim = mutableListOf<LatLon>()
        x = 0.0
        while (x <= 400) { perim.add(pt(x, 0.0)); x += 8 }
        y = 8.0
        while (y <= 400) { perim.add(pt(400.0, y)); y += 8 }
        x = 392.0
        while (x >= 0) { perim.add(pt(x, 400.0)); x -= 8 }
        y = 392.0
        while (y >= 0) { perim.add(pt(0.0, y)); y -= 8 }
        perim.forEachIndexed { i, p ->
            if (eng.state != TimingEngine.State.FINISHED) {
                eng.feed(GpsSample(t = t0 + i * 1000L, lat = p.lat, lon = p.lon))
            }
        }
        val s = eng.summary(t0)
        assertTrue("start" in ev.types, "circolare: partenza emessa all'uscita dalla zona")
        assertTrue("finish" in ev.types, "circolare: traguardo emesso al ritorno")
        assertEquals(0, s.offTrackEpisodes, "circolare: nessuna uscita dal corridoio")
        // Perimetro 1600 m a 8 m/s: partenza all'uscita del raggio, arrivo all'ingresso: ~197 s.
        assertTrue(abs(s.elapsedMs!! - 197_000) < 3000, "circolare: tempo ~197 s (${s.elapsedMs} ms)")
        assertTrue(s.elapsedMs!! > 15_000, "circolare: nessun arresto immediato")
    }

    @Test
    fun altaVelocitaCancelliScavalcatiTraDueFix() {
        val sp = pt(500.0)
        val track = Track(
            id = "t3", name = "Veloce", type = TrackType.OPEN, tolerance = 10.0,
            createdAt = 0, start = pt(0.0), finish = pt(1000.0),
            splits = mutableListOf(SplitPoint("P1", sp.lat, sp.lon)),
            path = straightPath(),
        )
        val ev = Events()
        val eng = TimingEngine(track, ev)
        // Fix ogni 40 m: nessun campione entro 10 m dai cancelli (-100+k*40, min |x| = 20).
        for (i in 0..40) {
            eng.feed(fix(-100.0 + i * 40.0, 0.0, t0 + i * 1000L))
            if (eng.state == TimingEngine.State.FINISHED) break
        }
        val s = eng.summary(t0)
        assertTrue("start" in ev.types, "alta velocità: partenza rilevata tra due fix")
        assertTrue("split" in ev.types, "alta velocità: parziale rilevato tra due fix")
        assertTrue("finish" in ev.types, "alta velocità: traguardo rilevato tra due fix")
        // 40 m/s, da x=-10 a x=990: 1000 m -> 25 s.
        assertTrue(abs(s.elapsedMs!! - 25_000) < 600, "alta velocità: tempo ~25 s (${s.elapsedMs} ms)")
    }

    @Test
    fun jsonRoundTripTrackESessione() {
        val track = Track(
            id = "t4", name = "Round<trip> & co.", type = TrackType.OPEN, tolerance = 15.0,
            createdAt = 123, start = pt(0.0), finish = pt(100.0),
            splits = mutableListOf(SplitPoint("S1", 45.1, 9.1)),
            path = mutableListOf(PathPoint(45.0, 9.0, 210.5), PathPoint(45.001, 9.001)),
        )
        val t2 = Json.parseTrack(Json.track(track))
        assertEquals(track, t2, "round-trip JSON del tracciato")

        val sess = Session(
            id = "s1", trackId = "t4", name = "Sessione 1", startedAt = 456,
            trackType = track.type, trackStart = track.start, trackFinish = track.finish,
            trackSplits = track.splits,
        )
        sess.samples.add(GpsSample(t0, 45.0, 9.0, 210.0, 5f, 12.3f, 90f))
        sess.samples.add(GpsSample(t0 + 1000, 45.0001, 9.0001))
        sess.motion.add(MotionSample(t0, 0.1f, 0.2f, 9.8f, 1.5f, -2.0f))
        sess.hr.add(HrSample(t0 + 500, 140))
        sess.result = SessionResult(SessionStatus.FINISHED, t0, t0 + 60_000, 60_000,
            listOf(SplitTime("S1", t0 + 30_000, 30_000)), 2500, 1)
        sess.status = SessionStatus.FINISHED
        val s2 = Json.parseSession(Json.session(sess))
        assertEquals(sess.samples, s2.samples, "round-trip campioni GPS")
        assertEquals(sess.motion, s2.motion, "round-trip telemetria di moto")
        assertEquals(sess.hr, s2.hr, "round-trip frequenza cardiaca")
        assertEquals(sess.result, s2.result, "round-trip risultato")
    }

    @Test
    fun gpxConFrequenzaCardiaca() {
        val sess = Session(
            id = "s2", trackId = "t", name = "HR test", startedAt = t0,
            trackType = TrackType.CIRCULAR, trackStart = pt(0.0), trackFinish = null,
            trackSplits = emptyList(),
        )
        for (i in 0..9) sess.samples.add(GpsSample(t0 + i * 1000L, 45.0 + i * 1e-5, 9.0))
        // HR a 0.5 Hz: associato al trkpt più vicino entro 5 s.
        for (i in 0..4) sess.hr.add(HrSample(t0 + i * 2000L + 300, 120 + i))
        val gpx = Gpx.session(sess)
        assertTrue(gpx.contains("<gpx version=\"1.1\""), "intestazione GPX 1.1")
        assertTrue(gpx.contains("<gpxtpx:hr>120</gpxtpx:hr>"), "HR iniziale presente")
        assertTrue(gpx.contains("<gpxtpx:hr>124</gpxtpx:hr>"), "HR finale presente")
        assertTrue(gpx.contains("<time>2023-11-14T"), "timestamp UTC presenti")
        // Punto lontano nel tempo da ogni HR: nessuna associazione.
        val far = Session(
            id = "s3", trackId = "t", name = "No HR", startedAt = t0,
            trackType = TrackType.CIRCULAR, trackStart = pt(0.0), trackFinish = null,
            trackSplits = emptyList(),
        )
        far.samples.add(GpsSample(t0 + 60_000, 45.0, 9.0))
        far.hr.add(HrSample(t0, 130))
        assertTrue(!Gpx.session(far).contains("gpxtpx:hr"), "HR oltre finestra: non associato")
    }

    @Test
    fun sessioneSenzaPartenzaNonHaTempo() {
        val track = Track(
            id = "t5", name = "Mai partito", type = TrackType.OPEN, tolerance = 10.0,
            createdAt = 0, start = pt(0.0), finish = pt(1000.0),
        )
        val eng = TimingEngine(track)
        // movimenti lontani dalla partenza
        for (i in 0..10) eng.feed(fix(5000.0 + i * 10, 0.0, t0 + i * 1000L))
        val s = eng.summary(t0)
        assertEquals(SessionStatus.ARMED, s.status)
        assertNull(s.elapsedMs, "nessun tempo senza attraversare la partenza")
    }
}
