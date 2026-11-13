package com.gpstelemetry.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Compatibilità con l'editor web (editor/ alla radice del repository):
 * il file web-editor-export.gpx è prodotto dal test end-to-end
 * tests/test-editor.js e deve essere importato dall'app con le linee
 * identiche (orientamento, larghezza, nomi).
 */
class WebEditorCompatTest {

    @Test
    fun importsGpxExportedByWebEditor() {
        val bytes = assertNotNull(javaClass.getResource("/web-editor-export.gpx")).readBytes()
        val t = GpxImport.parse(bytes, "id", 0).getOrThrow()
        assertEquals("Pista web", t.name)
        assertEquals(TrackType.CIRCULAR, t.type)
        assertEquals(15.0, t.tolerance)
        assertEquals(4, t.path.size)
        assertEquals(1, t.splits.size)
        assertNull(t.finish)
        // Linea di partenza perpendicolare al primo tratto, come nell'editor.
        val seg = Geo.bearingDeg(LatLon(t.path[0].lat, t.path[0].lon), LatLon(t.path[1].lat, t.path[1].lon))
        val diff = kotlin.math.abs((((t.start.headingDeg - seg) % 180) + 180) % 180 - 90)
        assert(diff < 0.1) { "scarto $diff" }
        assertEquals(30.0, t.start.widthM, 0.01)
    }
}
