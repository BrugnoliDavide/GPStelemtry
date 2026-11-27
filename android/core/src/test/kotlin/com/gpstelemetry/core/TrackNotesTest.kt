package com.gpstelemetry.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrackNotesTest {

    private val lat0 = 45.0
    private fun north(m: Double) = lat0 + m / 111_195.0

    @Test
    fun scattaUnaVoltaPerPassaggioEDopoSiRiarma() {
        val note = TrackNote(north(100.0), 9.0, "Frena al cartello")
        val trig = NoteTrigger(listOf(note))
        // Fix ogni 20 m verso nord: scatta una sola volta vicino al punto.
        val fired = (0..10).flatMap { trig.feed(north(it * 20.0), 9.0) }
        assertEquals(listOf(note), fired)
        // Si allontana oltre 75 m e ripassa: si rilegge (giro successivo).
        assertTrue(trig.feed(north(400.0), 9.0).isEmpty())
        val again = (20 downTo 0).flatMap { trig.feed(north(it * 20.0), 9.0) } // torna indietro lungo la strada
        assertEquals(1, again.size)
    }

    @Test
    fun nonLaSaltaAncheConFixRadi() {
        // 200 km/h con un fix al secondo: ~55 m tra un fix e l'altro.
        val note = TrackNote(north(110.0), 9.0, "Curva cieca")
        val trig = NoteTrigger(listOf(note))
        val fired = listOf(0.0, 55.0, 165.0, 220.0).flatMap { trig.feed(north(it), 9.0) }
        assertEquals(1, fired.size)
    }

    @Test
    fun lontanoNonScatta() {
        val trig = NoteTrigger(listOf(TrackNote(north(100.0), 9.002, "A lato"))) // ~157 m a est
        assertTrue((0..10).flatMap { trig.feed(north(it * 20.0), 9.0) }.isEmpty())
    }

    @Test
    fun pulisceEOrdinaLungoIlPercorso() {
        val path = (0..10).map { PathPoint(north(it * 50.0), 9.0) }
        val notes = listOf(
            TrackNote(north(400.0), 9.0, "  seconda   nota "),
            TrackNote(north(100.0), 9.0, "prima"),
            TrackNote(north(200.0), 9.0, "   "),
            TrackNote(north(300.0), 9.0, "x".repeat(200)),
        )
        val s = TrackNotes.sanitize(notes, path)
        assertEquals(listOf("prima", "x".repeat(140), "seconda nota"), s.map { it.text })
    }

    @Test
    fun jsonEGpxAndataERitorno() {
        val t = Track(
            "t", "Pista", TrackType.CIRCULAR, 15.0, 0, Gate(lat0, 9.0, 90.0, 30.0), null,
            path = (0..10).map { PathPoint(north(it * 50.0), 9.0) }.toMutableList(),
            notes = mutableListOf(TrackNote(north(100.0), 9.0, "Frena <forte> & scala"), TrackNote(north(300.0), 9.0, "Curva cieca")),
        )
        assertEquals(t.notes, Json.parseTrack(Json.track(t)).notes)
        val back = GpxImport.parse(Gpx.track(t).toByteArray(), "n", 0).getOrThrow()
        assertEquals(t.notes.map { it.text }, back.notes.map { it.text })
        assertEquals(t.notes[0].lat, back.notes[0].lat, 1e-6)
        // Le note non diventano parziali.
        assertEquals(0, back.splits.size)
        // Tracciato senza note: il JSON resta come prima.
        assertTrue(!Json.track(t.copy(notes = mutableListOf())).has("notes"))
    }
}
