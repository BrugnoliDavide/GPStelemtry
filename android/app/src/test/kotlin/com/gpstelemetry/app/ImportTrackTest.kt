package com.gpstelemetry.app

import android.net.Uri
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import com.gpstelemetry.core.Gate
import com.gpstelemetry.core.Gpx
import com.gpstelemetry.core.PathPoint
import com.gpstelemetry.core.SplitGate
import com.gpstelemetry.core.Track
import com.gpstelemetry.core.TrackType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.io.File

/** Importazione dal tasto in alto a destra della scheda Cronometra. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportTrackTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun importFile(f: File) {
        Settings.setTutorialDone(ctx, true)
        val main = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val frag = main.supportFragmentManager.findFragmentByTag("tab_timer") as TracksFragment
        TracksFragment::class.java.getDeclaredMethod("importTrack", Uri::class.java)
            .apply { isAccessible = true }.invoke(frag, Uri.fromFile(f))
        // Lettura in background, poi aggiornamento sul thread principale.
        repeat(20) {
            Thread.sleep(100)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun importsExportedTrackAndOffersEditor() {
        val t = Track(
            "x", "Kartodromo", TrackType.CIRCULAR, 18.0, 0, Gate(45.0, 9.0, 33.0, 25.0), null,
            splits = mutableListOf(SplitGate("Esse", Gate(45.001, 9.001, 80.0, 20.0))),
            path = mutableListOf(PathPoint(45.0, 9.0), PathPoint(45.001, 9.001), PathPoint(45.0, 9.002), PathPoint(45.0, 9.0)),
        )
        val f = File(ctx.cacheDir, "kartodromo.gpx").apply { writeText(Gpx.track(t)) }
        importFile(f)

        val tracks = TrackStore.listTracks(ctx)
        assertEquals(1, tracks.size)
        val imported = tracks[0]
        assertEquals("Kartodromo", imported.name)
        assertTrue(imported.id != "x") // nuovo tracciato, non sovrascrive
        assertEquals(33.0, imported.start.headingDeg, 0.01)
        assertEquals(listOf("Esse"), imported.splits.map { it.name })

        val dialog = ShadowDialog.getLatestDialog() as? AlertDialog
        assertNotNull(dialog)
        assertTrue(dialog!!.isShowing)
    }

    @Test
    fun invalidFileShowsNoTrack() {
        val f = File(ctx.cacheDir, "foto.gpx").apply { writeText("questo non è un gpx") }
        importFile(f)
        assertTrue(TrackStore.listTracks(ctx).isEmpty())
    }
}
