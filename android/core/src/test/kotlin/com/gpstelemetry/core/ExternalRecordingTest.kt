package com.gpstelemetry.core

import java.io.ByteArrayOutputStream
import java.time.Instant
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Registrazioni da altri dispositivi: un anello di raggio 150 m (942 m)
 * percorso a 20 m/s con un punto al secondo, come un Insta360 o un orologio.
 */
class ExternalRecordingTest {
    private val lat0 = 45.0
    private val mPerDegLon = 111_320.0 * cos(lat0 * PI / 180)
    private val t0 = 1_794_000_000_000L
    private val r = 150.0
    private val lapM = 2 * PI * r

    private fun pt(x: Double, y: Double) = LatLon(lat0 + y / 111_320.0, 9.0 + x / mPerDegLon)

    /** Punto dell'anello dopo [s] metri dalla partenza (verso est, senso antiorario). */
    private fun ring(s: Double): LatLon {
        val a = s / r
        return pt(r * sin(a), r * (1 - cos(a)))
    }

    private val track = Track(
        "anello", "Anello", TrackType.CIRCULAR, 15.0, 0,
        pt(0.0, 0.0).let { Gate(it.lat, it.lon, 0.0, 30.0) }, null,
        splits = mutableListOf(SplitGate("Metà", ring(lapM / 2).let { Gate(it.lat, it.lon, 0.0, 30.0) })),
        path = MutableList(121) { i -> ring(lapM * i / 120).let { PathPoint(it.lat, it.lon) } },
    )

    /** Registrazione: 10 s prima della partenza, [laps] giri, 10 s dopo; un punto ogni [dtMs]. */
    private fun samples(laps: Double = 3.0, speed: Double = 20.0, dtMs: Long = 1000, offset: (Int) -> Pair<Double, Double> = { 0.0 to 0.0 }): List<GpsSample> {
        val total = laps * lapM + 20 * speed
        val n = (total / (speed * dtMs / 1000.0)).toInt()
        return (0..n).map { i ->
            val s = i * speed * dtMs / 1000.0 - 10 * speed
            val p = ring(((s % lapM) + lapM) % lapM)
            val (dx, dy) = offset(i)
            GpsSample(t = t0 + i * dtMs, lat = p.lat + dy / 111_320.0, lon = p.lon + dx / mPerDegLon, ele = 200.0)
        }
    }

    private fun gpx(s: List<GpsSample>, creator: String = "Insta360 Studio", withHr: Boolean = false) = buildString {
        append("<?xml version=\"1.0\"?>\n<gpx version=\"1.1\" creator=\"$creator\" xmlns=\"http://www.topografix.com/GPX/1/1\"")
        append(" xmlns:gpxtpx=\"http://www.garmin.com/xmlschemas/TrackPointExtension/v1\"><trk><trkseg>\n")
        s.forEachIndexed { i, p ->
            append("<trkpt lat=\"${p.lat}\" lon=\"${p.lon}\"><ele>200</ele><time>${Instant.ofEpochMilli(p.t)}</time>")
            if (withHr) append("<extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>${120 + i % 30}</gpxtpx:hr></gpxtpx:TrackPointExtension></extensions>")
            append("</trkpt>\n")
        }
        append("</trkseg></trk></gpx>")
    }.toByteArray()

    @Test
    fun treGiriDaGpxInsta360() {
        val rec = ExternalRecording.parse(gpx(samples(), withHr = true))
        assertEquals("GPX", rec.format)
        assertEquals("Insta360 Studio", rec.creator)
        val an = ExternalRecording.analyze(track, rec)
        assertEquals(3, an.laps.size)
        val expected = (lapM / 20.0 * 1000).roundToLong()
        an.laps.forEach { lap ->
            assertEquals(SessionStatus.FINISHED, lap.result.status)
            assertTrue(abs(lap.result.elapsedMs!! - expected) < 50, "giro ${lap.result.elapsedMs} ≈ $expected")
            assertEquals(1, lap.result.splits.size) // parziale a metà giro
            assertTrue(lap.hr.isNotEmpty())
            assertTrue(lap.samples.first().t <= lap.result.startTime!! && lap.samples.last().t >= lap.result.finishTime!!)
        }
        // Giri consecutivi: il traguardo di uno è la partenza del successivo.
        assertEquals(an.laps[0].result.finishTime, an.laps[1].result.startTime)
        assertTrue(an.inside > 0.99)
        assertNotNull(an.best)
    }

    @Test
    fun menoDel70PerCentoDentroErrore() {
        // Dal punto 60 in poi la registrazione è 200 m fuori dal tracciato.
        val s = samples(offset = { i -> if (i >= 60) 200.0 to 0.0 else 0.0 to 0.0 })
        val e = assertFailsWith<ExternalRecording.ImportException> { ExternalRecording.analyze(track, ExternalRecording.parse(gpx(s))) }
        assertTrue(e.message!!.contains("dentro il tracciato"), e.message)
    }

    @Test
    fun altroPostoErrore() {
        val far = samples().map { it.copy(lat = it.lat + 0.5) } // 55 km più a nord
        val e = assertFailsWith<ExternalRecording.ImportException> { ExternalRecording.analyze(track, ExternalRecording.parse(gpx(far))) }
        assertTrue(e.message!!.contains("non è stata fatta su questo tracciato"), e.message)
    }

    @Test
    fun saltiImpossibiliErrore() {
        // Un punto su dieci salta di 2 km: segnale inaffidabile.
        val s = samples(offset = { i -> if (i % 10 == 5) 2000.0 to 0.0 else 0.0 to 0.0 })
        val e = assertFailsWith<ExternalRecording.ImportException> { ExternalRecording.analyze(track, ExternalRecording.parse(gpx(s))) }
        assertTrue(e.message!!.contains("incoerente"), e.message)
    }

    @Test
    fun puntiTroppoRadiErrore() {
        val e = assertFailsWith<ExternalRecording.ImportException> {
            ExternalRecording.analyze(track, ExternalRecording.parse(gpx(samples(laps = 30.0, dtMs = 10_000))))
        }
        assertTrue(e.message!!.contains("troppo radi"), e.message)
    }

    @Test
    fun nessunGiroCompletoErrore() {
        val e = assertFailsWith<ExternalRecording.ImportException> {
            ExternalRecording.analyze(track, ExternalRecording.parse(gpx(samples(laps = 0.6))))
        }
        assertTrue(e.message!!.contains("Nessun giro completo"), e.message)
    }

    @Test
    fun senzaOrariErrore() {
        val noTime = String(gpx(samples())).replace(Regex("<time>[^<]*</time>"), "").toByteArray()
        assertFailsWith<ExternalRecording.ImportException> { ExternalRecording.parse(noTime) }
    }

    @Test
    fun formatoSconosciutoErrore() {
        assertFailsWith<ExternalRecording.ImportException> { ExternalRecording.parse("ciao".toByteArray()) }
    }

    @Test
    fun tcxDiUnOrologio() {
        val s = samples(laps = 1.2)
        val tcx = buildString {
            append("<?xml version=\"1.0\"?><TrainingCenterDatabase xmlns=\"http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2\">")
            append("<Activities><Activity><Lap><Track>")
            s.forEach { p ->
                append("<Trackpoint><Time>${Instant.ofEpochMilli(p.t)}</Time><Position><LatitudeDegrees>${p.lat}</LatitudeDegrees>")
                append("<LongitudeDegrees>${p.lon}</LongitudeDegrees></Position><HeartRateBpm><Value>140</Value></HeartRateBpm></Trackpoint>")
            }
            append("</Track></Lap><Creator><Name>Forerunner 265</Name></Creator></Activity></Activities></TrainingCenterDatabase>")
        }.toByteArray()
        val rec = ExternalRecording.parse(tcx)
        assertEquals("TCX", rec.format)
        assertEquals("Forerunner 265", rec.creator)
        assertEquals(s.size, rec.hr.size)
        assertEquals(1, ExternalRecording.analyze(track, rec).laps.size)
    }

    @Test
    fun nmeaDiUnaActionCam() {
        val s = samples(laps = 1.2)
        val text = s.joinToString("\n") { p ->
            val dt = java.time.LocalDateTime.ofInstant(Instant.ofEpochMilli(p.t), java.time.ZoneOffset.UTC)
            fun dm(v: Double): String { val d = v.toInt(); return String.format(java.util.Locale.US, "%d%09.6f", d, (v - d) * 60) }
            "\$GPRMC,%02d%02d%02d.00,A,%s,N,%s,E,38.9,90.0,%02d%02d%02d,,,A*00".format(
                dt.hour, dt.minute, dt.second, dm(p.lat), "00" + dm(p.lon), dt.dayOfMonth, dt.monthValue, dt.year % 100,
            )
        }
        val rec = ExternalRecording.parse(text.toByteArray())
        assertEquals("NMEA", rec.format)
        assertEquals(s.size, rec.samples.size)
        assertTrue(abs(rec.samples[3].lat - s[3].lat) < 1e-6 && abs(rec.samples[3].lon - s[3].lon) < 1e-6)
        assertEquals(s[3].t, rec.samples[3].t)
        assertEquals(1, ExternalRecording.analyze(track, rec).laps.size)
    }

    @Test
    fun fitDiGarmin() {
        val s = samples(laps = 1.2)
        val fit = FitWriter.records(s)
        val rec = ExternalRecording.parse(fit)
        assertEquals("FIT", rec.format)
        assertEquals(s.size, rec.samples.size)
        assertTrue(abs(rec.samples[10].lat - s[10].lat) < 1e-6)
        assertEquals(s[10].t, rec.samples[10].t) // anche con orari compressi
        assertEquals(150, rec.hr.first().bpm)
        assertEquals(1, ExternalRecording.analyze(track, rec).laps.size)
    }

    @Test
    fun duplicatiRiconosciuti() {
        val an = ExternalRecording.analyze(track, ExternalRecording.parse(gpx(samples())))
        val starts = listOf(an.laps[1].result.startTime!! + 300)
        assertTrue(ExternalRecording.isDuplicate(an.laps[1], starts))
        assertTrue(!ExternalRecording.isDuplicate(an.laps[0], starts))
    }

    /**
     * Scrittore FIT minimo per i test: un messaggio "record" con orario
     * completo ogni 5 punti, gli altri con intestazione a orario compresso.
     */
    private object FitWriter {
        private const val FIT_EPOCH_S = 631_065_600L

        fun records(s: List<GpsSample>): ByteArray {
            val data = ByteArrayOutputStream()
            fun u8(v: Int) = data.write(v and 0xFF)
            fun u16(v: Int) { u8(v); u8(v shr 8) }
            fun u32(v: Long) { for (k in 0 until 4) u8((v shr (8 * k)).toInt()) }
            // Definizione locale 0: record completo (timestamp, lat, lon, altitudine, hr).
            u8(0x40); u8(0); u8(0); u16(20); u8(5)
            u8(253); u8(4); u8(0x86)
            u8(0); u8(4); u8(0x85)
            u8(1); u8(4); u8(0x85)
            u8(2); u8(2); u8(0x84)
            u8(3); u8(1); u8(0x02)
            // Definizione locale 1: record senza timestamp (per l'intestazione compressa).
            u8(0x41); u8(0); u8(0); u16(20); u8(3)
            u8(0); u8(4); u8(0x85)
            u8(1); u8(4); u8(0x85)
            u8(3); u8(1); u8(0x02)
            fun semi(d: Double) = (d / 180.0 * 2147483648.0).roundToLong() and 0xFFFFFFFFL
            s.forEachIndexed { i, p ->
                val ts = p.t / 1000 - FIT_EPOCH_S
                if (i % 5 == 0) {
                    u8(0x00); u32(ts); u32(semi(p.lat)); u32(semi(p.lon)); u16(((200 + 500) * 5)); u8(150)
                } else {
                    u8(0x80 or (1 shl 5) or (ts and 0x1F).toInt()); u32(semi(p.lat)); u32(semi(p.lon)); u8(150)
                }
            }
            val body = data.toByteArray()
            val out = ByteArrayOutputStream()
            out.write(byteArrayOf(14, 0x20, 0x5C, 0x08))
            out.write(byteArrayOf(body.size.toByte(), (body.size shr 8).toByte(), (body.size shr 16).toByte(), (body.size shr 24).toByte()))
            out.write(".FIT".toByteArray())
            out.write(byteArrayOf(0, 0))
            out.write(body)
            out.write(byteArrayOf(0, 0))
            return out.toByteArray()
        }
    }
}
