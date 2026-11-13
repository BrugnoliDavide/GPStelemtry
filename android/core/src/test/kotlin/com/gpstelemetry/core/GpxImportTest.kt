package com.gpstelemetry.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GpxImportTest {
    private val lat0 = 45.0
    private val mLon = 111_320.0 * cos(lat0 * PI / 180)
    private fun at(x: Double, y: Double) = PathPoint(lat0 + y / 111_320.0, 9.0 + x / mLon)

    private fun parse(s: String) = GpxImport.parse(s.toByteArray(), "new-id", 123L)

    @Test
    fun roundTripOfOwnExportIsIdentical() {
        val path = MutableList(100) { i ->
            val a = i / 100.0 * 2 * PI
            at(300 * cos(a), 200 * sin(a)).copy(ele = 100.0 + i)
        }
        val t = Track(
            "old", "Pista <del> Lago & co", TrackType.CIRCULAR, 22.0, 1,
            Gate(path[0].lat, path[0].lon, 37.5, 44.0), null,
            splits = mutableListOf(
                SplitGate("Curva Nord", Gate(path[30].lat, path[30].lon, 120.25, 30.0)),
                SplitGate("Parabolica", Gate(path[60].lat, path[60].lon, 5.0, 25.0)),
            ),
            path = path,
        )
        val back = GpxImport.parse(Gpx.track(t).toByteArray(), "new-id", 123L).getOrThrow()
        assertEquals("new-id", back.id)
        assertEquals("Pista <del> Lago & co", back.name)
        assertEquals(TrackType.CIRCULAR, back.type)
        assertEquals(22.0, back.tolerance)
        assertEquals(37.5, back.start.headingDeg, 0.01)
        assertEquals(44.0, back.start.widthM, 0.01)
        assertEquals(listOf("Curva Nord", "Parabolica"), back.splits.map { it.name })
        assertEquals(120.25, back.splits[0].gate.headingDeg, 0.01)
        assertEquals(25.0, back.splits[1].gate.widthM, 0.01)
        assertEquals(100, back.path.size)
        assertEquals(t.path[10].lat, back.path[10].lat, 1e-6)
        assertEquals(110.0, back.path[10].ele!!, 0.05)
        assertNull(back.finish)
        assertTrue(!back.published && back.remoteId == null)
    }

    @Test
    fun openTrackRoundTrip() {
        val path = MutableList(20) { at(it * 50.0, 0.0) }
        val t = Track("o", "Salita", TrackType.OPEN, 15.0, 1, Gate(path[0].lat, path[0].lon, 0.0, 30.0),
            Gate(path[19].lat, path[19].lon, 0.0, 30.0), path = path)
        val back = GpxImport.parse(Gpx.track(t).toByteArray(), "n", 0).getOrThrow()
        assertEquals(TrackType.OPEN, back.type)
        assertEquals(path[19].lon, back.finish!!.lon, 1e-6)
    }

    @Test
    fun genericLoopTrackBecomesCircuitWithPerpendicularStart() {
        // Anello percorso verso est all'inizio (y = 0, x crescente).
        val pts = (0..40).joinToString("\n") { i ->
            val a = i / 40.0 * 2 * PI
            val p = at(250 * sin(a), 250 - 250 * cos(a))
            "<trkpt lat=\"${p.lat}\" lon=\"${p.lon}\"><ele>12</ele></trkpt>"
        }
        val gpx = """<?xml version="1.0"?><gpx version="1.1" creator="Strava"><trk><name>Giro del lago</name><trkseg>$pts</trkseg></trk></gpx>"""
        val t = parse(gpx).getOrThrow()
        assertEquals("Giro del lago", t.name)
        assertEquals(TrackType.CIRCULAR, t.type)
        assertEquals(GpxImport.DEFAULT_TOLERANCE, t.tolerance)
        // Percorso verso est (90°) → linea perpendicolare nord-sud (0°).
        assertTrue(abs(t.start.headingDeg) < 10 || abs(t.start.headingDeg - 180) < 10, "heading ${t.start.headingDeg}")
        assertTrue(t.splits.isEmpty())
    }

    @Test
    fun genericRouteWithNamedWaypoints() {
        val p = (0..10).map { at(it * 100.0, 0.0) }
        val gpx = """<gpx version="1.1"><metadata><name>Prova speciale</name></metadata>
            <wpt lat="${p[0].lat}" lon="${p[0].lon}"><name>Start</name></wpt>
            <wpt lat="${p[4].lat}" lon="${p[4].lon}"><name>Tornante</name></wpt>
            <wpt lat="${p[10].lat}" lon="${p[10].lon}"><name>Finish</name></wpt>
            <rte>${p.joinToString("") { "<rtept lat=\"${it.lat}\" lon=\"${it.lon}\"/>" }}</rte></gpx>"""
        val t = parse(gpx).getOrThrow()
        assertEquals("Prova speciale", t.name)
        assertEquals(TrackType.OPEN, t.type)
        assertEquals(listOf("Tornante"), t.splits.map { it.name })
        assertEquals(p[10].lon, t.finish!!.lon, 1e-9)
        assertEquals(11, t.path.size)
    }

    @Test
    fun invalidFilesAreRejected() {
        assertTrue(parse("non è xml").isFailure)
        assertTrue(parse("<kml></kml>").isFailure)
        assertTrue(parse("<gpx version=\"1.1\"></gpx>").isFailure)
        // DTD/entità esterne rifiutate.
        val xxe = """<?xml version="1.0"?><!DOCTYPE gpx [<!ENTITY x SYSTEM "file:///etc/passwd">]><gpx><metadata><name>&x;</name></metadata></gpx>"""
        assertTrue(parse(xxe).isFailure)
    }
}
