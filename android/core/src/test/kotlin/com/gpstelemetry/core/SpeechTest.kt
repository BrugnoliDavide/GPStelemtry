package com.gpstelemetry.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SpeechTest {
    private val ref = Sectors.Stat(best = 78_000, avg = 80_000, count = 3)

    @Test
    fun tempiParlati() {
        assertEquals("1 minuto e 13 secondi", Speech.time(73_000))
        assertEquals("41,3 secondi", Speech.time(41_270))
        assertEquals("2 minuti", Speech.time(120_020))
        assertEquals("1 secondo", Speech.time(1_000))
        assertEquals("4 decimi", Speech.gap(-420))
        assertEquals("1 decimo", Speech.gap(90))
        assertEquals("5 secondi", Speech.gap(-5_000))
        assertEquals("2,3 secondi", Speech.gap(2_310))
    }

    @Test
    fun frasiDeiSettori() {
        assertEquals(
            "Settore 3, Polledrara, completato in 1 minuto e 13 secondi. Settore viola! 5 secondi meglio del record.",
            Speech.sector(3, "Polledrara", 73_000, ref),
        )
        assertEquals(
            "Settore 1 completato in 1 minuto e 20,5 secondi. 2,5 secondi più lento del record.",
            Speech.sector(1, null, 80_500, ref),
        )
        assertEquals("Settore 2 completato in 30 secondi. Primo tempo.", Speech.sector(2, " ", 30_000, null))
        assertEquals("Giro completato in 1 minuto e 18 secondi. Pari al record.", Speech.lap(78_020, ref))
        assertEquals("Giro completato in 1 minuto e 17 secondi. Nuovo record! 1 secondo meglio del record.", Speech.lap(77_000, ref))
    }

    @Test
    fun numeriENomiConNeutralizzazioni() {
        val g = Gate(45.0, 9.0)
        val splits = listOf(
            SplitGate("Curva Nord", g),
            SplitGate("Neutralizzazione 1", g, neutral = true),
            SplitGate("Fine neutralizzazione 1", g),
        )
        // Settori: S1 (Curva Nord), S2, N1, S3.
        assertEquals("Curva Nord", Speech.sectorName(splits, 0))
        assertNull(Speech.sectorName(splits, 1))
        assertNull(Speech.sectorName(splits, 3))
        assertEquals(listOf(1, 2, 3), listOf(0, 1, 3).map { Speech.sectorNumber(splits, it) })
    }
}
