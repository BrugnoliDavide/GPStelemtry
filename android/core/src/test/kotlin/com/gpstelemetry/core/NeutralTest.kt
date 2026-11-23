package com.gpstelemetry.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Neutralizzazioni: settori in cui il cronometro è fermo.
 * Rettilineo di 1000 m percorso a 10 m/s; neutralizzazione tra x=400 e x=600
 * attraversata più lentamente (5 m/s, quindi 40 s reali) e con una
 * deviazione fuori tolleranza che NON deve essere contata.
 */
class NeutralTest {
    private val lat0 = 45.0
    private val mPerDegLon = 111_320.0 * cos(lat0 * PI / 180)
    private val t0 = 1_700_000_000_000L

    private fun pt(x: Double, y: Double = 0.0) = LatLon(lat0 + y / 111_320.0, 9.0 + x / mPerDegLon)
    private fun gate(x: Double) = pt(x).let { Gate(it.lat, it.lon, 0.0, 30.0) }

    private fun track() = Track(
        id = "n", name = "Con neutralizzazione", type = TrackType.OPEN, tolerance = 10.0,
        createdAt = 0, start = gate(0.0), finish = gate(1000.0),
        splits = mutableListOf(
            SplitGate("P1", gate(200.0)),
            SplitGate("Neutro", gate(400.0), neutral = true),
            SplitGate("Fine neutro", gate(600.0)),
        ),
        path = MutableList(21) { i -> pt(i * 50.0).let { PathPoint(it.lat, it.lon) } },
    )

    private class Events : TimingEngine.Listener {
        val types = mutableListOf<String>()
        override fun onNeutralEnter(t: Long, sectorIndex: Int) { types.add("n-enter:$sectorIndex") }
        override fun onNeutralExit(t: Long, durationMs: Long) { types.add("n-exit") }
        override fun onOffTrackEnter(t: Long, episodes: Int) { types.add("off") }
        override fun onFinish(t: Long, elapsedMs: Long) { types.add("finish") }
    }

    @Test
    fun ilTempoNonScorreNellaNeutralizzazione() {
        val ev = Events()
        val eng = TimingEngine(track(), ev)
        var t = t0
        var x = -50.0
        var frozenAt: Long? = null
        while (x <= 1050.0 && eng.state != TimingEngine.State.FINISHED) {
            val inside = x > 400 && x < 600
            // Dentro la neutralizzazione: lento e fuori tracciato (y = 40 m).
            eng.feed(GpsSample(t, pt(x, if (inside && x in 450.0..550.0) 40.0 else 0.0).lat, pt(x).lon))
            if (eng.isNeutral) {
                val e = eng.elapsedMs(t)
                if (frozenAt == null) frozenAt = e
                assertEquals(frozenAt, e, "cronometro fermo nella neutralizzazione")
            }
            val v = if (inside) 5.0 else 10.0
            x += v
            t += 1000
        }
        val s = eng.summary(t)
        assertEquals(listOf("n-enter:2","n-exit", "finish"), ev.types, "eventi (nessuna uscita contata)")
        assertEquals(0, s.offTrackEpisodes, "uscite nella neutralizzazione non contate")

        // 800 m cronometrati a 10 m/s = 80 s; neutralizzazione 200 m a 5 m/s = 40 s.
        assertTrue(abs(s.elapsedMs!! - 80_000) < 1500, "giro effettivo ~80 s (${s.elapsedMs})")
        assertTrue(abs(s.neutralMs - 40_000) < 1500, "neutralizzazione ~40 s (${s.neutralMs})")
        assertTrue(abs(Neutral.rawElapsed(s)!! - 120_000) < 1500, "tempo reale ~120 s")

        // Parziali: l'uscita registra la durata reale e un tempo effettivo invariato.
        val exit = s.splits[2]
        assertTrue(exit.neutral && abs(exit.neutralMs - 40_000) < 1500)
        assertEquals(s.splits[1].elapsed, exit.elapsed, "tempo effettivo fermo tra ingresso e uscita")
        val d = Sectors.durations(s.splits, s.elapsedMs)
        assertEquals(4, d.size)
        assertEquals(0L, d[2], "il settore neutralizzato non conta")
        assertTrue(abs(d.sum() - s.elapsedMs!!) < 2)
        assertEquals(listOf(false, false, true, false), Neutral.sectorMask(s.splits, 4))

        // Velocità media sul solo tempo cronometrato: 800 m in ~80 s = ~36 km/h.
        val session = Session(
            "s", "n", "n", t0, TrackType.OPEN, track().start, track().finish, track().splits,
        ).apply { result = s }
        var tt = t0
        var xx = -50.0
        while (xx <= 1050.0) {
            session.samples.add(GpsSample(tt, pt(xx).lat, pt(xx).lon))
            xx += if (xx > 400 && xx < 600) 5.0 else 10.0
            tt += 1000
        }
        val avg = LapStats.compute(session).avgSpeedKmh!!
        assertTrue(abs(avg - 36.0) < 1.5, "media senza neutralizzazione ~36 km/h ($avg)")
    }

    @Test
    fun mascheraEtichetteEPersistenza() {
        val tr = track()
        assertEquals(listOf(false, false, true, false), Neutral.sectorMask(tr.splits))
        assertEquals(listOf("P1", "N1", "N1 fine"), Neutral.gateLabels(tr.splits))
        assertEquals(listOf("S1", "S2", "N1", "S3"), Neutral.sectorLabels(Neutral.sectorMask(tr.splits)))
        assertEquals(1, Neutral.count(tr.splits))

        // L'ultimo parziale non può aprire una neutralizzazione.
        val bad = listOf(SplitGate("A", gate(1.0)), SplitGate("B", gate(2.0), neutral = true))
        assertFalse(Neutral.isEntry(bad, 1))
        assertFalse(Neutral.sanitize(bad)[1].neutral)

        // JSON e GPX conservano il contrassegno.
        val back = Json.parseTrack(Json.track(tr))
        assertEquals(tr.splits, back.splits)
        val gpx = GpxImport.parse(Gpx.track(tr).toByteArray(), "x", 0).getOrThrow()
        assertEquals(listOf(false, true, false), gpx.splits.map { it.neutral })

        val r = SessionResult(
            SessionStatus.FINISHED, 0, 1, 80_000,
            listOf(SplitTime("A", 1, 10_000), SplitTime("B", 2, 30_000, neutral = true, neutralMs = 40_000)),
            0, 0, neutralMs = 40_000,
        )
        assertEquals(r, Json.parseResultJson(Json.resultJson(r)))
    }

    @Test
    fun inserimentoLungoIlPercorso() {
        val tr = track()
        val splits = mutableListOf(SplitGate("P1", gate(200.0)), SplitGate("P2", gate(800.0)))
        // Uscita indicata prima dell'ingresso: vengono riordinati.
        val i = Neutral.addZone(splits, tr.path, gate(600.0), gate(400.0), 1)
        assertEquals(1, i)
        assertEquals(listOf("P1", "N1", "N1 fine", "P2"), Neutral.gateLabels(splits))
        assertTrue(abs(Geo.distanceAlongPath(tr.path, splits[1].gate.center)!! - 400) < 1)
    }
}
