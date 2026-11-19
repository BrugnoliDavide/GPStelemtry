package com.gpstelemetry.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrackFactsTest {

    private fun g(lat: Double) = Gate(lat, 9.0, 0.0, 20.0)

    private fun track(path: List<PathPoint>, splits: List<SplitGate> = emptyList()) = Track(
        "t", "Prova", TrackType.CIRCULAR, 15.0, 0, g(45.0), null,
        splits = splits.toMutableList(), path = path.toMutableList(),
    )

    @Test
    fun lunghezzaEDislivelloConIsteresi() {
        // Salita di 20 m, rumore di ±1 m, discesa di 10 m.
        val eles = listOf(100.0, 101.0, 100.0, 110.0, 120.0, 119.0, 120.0, 115.0, 110.0)
        val path = eles.mapIndexed { i, e -> PathPoint(45.0 + i * 0.001, 9.0, e) }
        val f = TrackFacts.of(track(path))
        assertEquals(20.0, f.elevGainM!!, 0.01)
        assertEquals(10.0, f.elevLossM!!, 0.01)
        assertEquals(100.0, f.minEleM)
        assertEquals(120.0, f.maxEleM)
        assertEquals(889.0, f.lengthM!!, 2.0) // 8 tratti da ~111 m
    }

    @Test
    fun senzaQuotaNePercorso() {
        val f = TrackFacts.of(track(emptyList()))
        assertNull(f.lengthM)
        assertNull(f.elevGainM)
        assertNull(f.minEleM)
    }

    @Test
    fun settoriENeutralizzazioni() {
        val splits = listOf(SplitGate("Esse", g(45.001)), SplitGate("N", g(45.002), neutral = true), SplitGate("Uscita", g(45.003)))
        val f = TrackFacts.of(track(emptyList(), splits))
        assertEquals(3, f.timedSectors)
        assertEquals(1, f.neutralZones)
    }

    @Test
    fun miglioriSettoriDallaClassifica() {
        val splits = listOf(SplitGate("Esse", g(45.001)), SplitGate("N", g(45.002), neutral = true), SplitGate("Parziale 3", g(45.003)))
        val t = track(emptyList(), splits)
        val board = listOf(
            LeaderboardEntry(1, "anna", 30_000, listOf(10_000, 10_000, 0, 10_000), 0),
            LeaderboardEntry(2, "io", 31_000, listOf(9_000, 11_500, 0, 10_500), 0, isMe = true),
            LeaderboardEntry(3, "oltre10", 40_000, emptyList(), 0), // senza settori: ignorato
        )
        val sb = SectorBoard.of(t, board)
        assertEquals(listOf("S1", "S2", "S3"), sb.rows.map { it.label })
        assertEquals(listOf(0, 1, 3), sb.rows.map { it.index })
        assertEquals("Esse", sb.rows[0].name)
        assertNull(sb.rows[2].name) // "Parziale 3" non è un nome vero
        assertEquals(9_000L, sb.rows[0].bestMs)
        assertEquals("io", sb.rows[0].bestDriver)
        assertEquals(0L, sb.rows[0].gapMs) // settore viola mio
        assertEquals(1_500L, sb.rows[1].gapMs)
        assertEquals(29_000L, sb.idealLapMs)
        assertEquals(31_000L, sb.myLapMs)
    }

    @Test
    fun classificaVuota() {
        val sb = SectorBoard.of(track(emptyList(), listOf(SplitGate("A", g(45.001)))), emptyList())
        assertEquals(2, sb.rows.size)
        assertTrue(sb.rows.all { it.bestMs == null })
        assertNull(sb.idealLapMs)
    }
}
