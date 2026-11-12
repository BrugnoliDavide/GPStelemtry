package com.gpstelemetry.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SectorsTest {

    private fun result(total: Long, vararg splitElapsed: Long, status: SessionStatus = SessionStatus.FINISHED) =
        SessionResult(
            status = status,
            startTime = 0,
            finishTime = total,
            elapsedMs = total,
            splits = splitElapsed.mapIndexed { i, e -> SplitTime("P${i + 1}", e, e) },
            offTrackMs = 0,
            offTrackEpisodes = 0,
        )

    @Test
    fun durationsIncludeLastSectorOnlyWhenFinished() {
        val splits = listOf(SplitTime("P1", 0, 30_000), SplitTime("P2", 0, 70_000))
        assertEquals(listOf(30_000L, 40_000L), Sectors.durations(splits, null))
        assertEquals(listOf(30_000L, 40_000L, 20_000L), Sectors.durations(splits, 90_000))
        assertEquals(listOf(55_000L), Sectors.durations(emptyList(), 55_000))
    }

    @Test
    fun benchmarkComputesBestAndAveragePerSector() {
        val history = listOf(
            result(100_000, 40_000),          // settori 40 / 60
            result(90_000, 30_000),           // settori 30 / 60
            result(110_000, 50_000),          // settori 50 / 60
            result(80_000, 20_000, status = SessionStatus.ABORTED), // ignorata
            result(70_000),                   // parziali diversi: solo giro
        )
        val b = Sectors.benchmark(history, splitCount = 1)
        assertEquals(2, b.sectors.size)
        assertEquals(Sectors.Stat(30_000, 40_000, 3), b.sectors[0])
        assertEquals(Sectors.Stat(60_000, 60_000, 3), b.sectors[1])
        assertEquals(Sectors.Stat(70_000, 92_500, 4), b.lap)
    }

    @Test
    fun ratingColors() {
        val ref = Sectors.Stat(best = 30_000, avg = 40_000, count = 3)
        assertEquals(Sectors.Rating.BEST, Sectors.rate(29_999, ref))
        assertEquals(Sectors.Rating.BETTER, Sectors.rate(30_000, ref))
        assertEquals(Sectors.Rating.BETTER, Sectors.rate(40_000, ref))
        assertEquals(Sectors.Rating.WORSE, Sectors.rate(40_001, ref))
        // Primo tempo in assoluto: record.
        assertEquals(Sectors.Rating.BEST, Sectors.rate(99_000, null))
        assertEquals(-500L, Sectors.deltaToBest(29_500, ref))
        assertNull(Sectors.deltaToBest(1, null))
    }

    @Test
    fun emptyHistoryGivesEmptyBenchmark() {
        val b = Sectors.benchmark(emptyList(), splitCount = 2)
        assertEquals(3, b.sectors.size)
        assertEquals(listOf<Sectors.Stat?>(null, null, null), b.sectors)
        assertNull(b.lap)
    }
}
