package com.gpstelemetry.app

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.widget.ImageButton
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi")
class TutorialTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun startsOnFirstLaunchOnly() {
        val main = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertEquals(TutorialActivity::class.java.name, shadowOf(main).nextStartedActivity.component?.className)

        Settings.setTutorialDone(ctx, true)
        val again = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val next = shadowOf(again).nextStartedActivity
        assertTrue(next == null || next.component?.className != TutorialActivity::class.java.name)
    }

    @Test
    fun skipLeadsToDisclaimerAndUnderstoodCompletes() {
        val t = Robolectric.buildActivity(TutorialActivity::class.java).setup().get()
        t.findViewById<View>(R.id.tutSkip).performClick()
        idle()
        val next = t.findViewById<com.google.android.material.button.MaterialButton>(R.id.tutNext)
        assertEquals(t.getString(R.string.tut_understood), next.text.toString())
        assertEquals(View.GONE, t.findViewById<View>(R.id.tutBack).visibility)
        assertFalse(Settings.tutorialDone(ctx))
        next.performClick()
        assertTrue(Settings.tutorialDone(ctx))
        assertTrue(Settings.disclaimerAcceptedAt(ctx) > 0)
        assertTrue(t.isFinishing)
    }

    @Test
    fun tryStepAdvancesOnlyWithPlayAndDemoTrackIsNotSaved() {
        val t = Robolectric.buildActivity(TutorialActivity::class.java).setup().get()
        t.showStep(4) // prova
        idle()
        assertEquals(View.INVISIBLE, t.findViewById<View>(R.id.tutNext).visibility)
        t.findViewById<View>(R.id.tutContent).findViewById<ImageButton>(R.id.btnPlay).performClick()
        idle()
        assertEquals(View.VISIBLE, t.findViewById<View>(R.id.tutNext).visibility) // passo sicurezza
        assertTrue(TrackStore.listTracks(ctx).isEmpty())
        assertNull(TrackStore.getTrack(ctx, "tutorial-demo"))
    }

    @Test
    fun screenshots() {
        val t = Robolectric.buildActivity(TutorialActivity::class.java).setup().get()
        listOf(0 to "20_tutorial_welcome", 1 to "21_tutorial_create", 2 to "22_tutorial_timer",
            4 to "23_tutorial_try", 5 to "24_tutorial_safety", 6 to "25_tutorial_disclaimer").forEach { (i, name) ->
            t.showStep(i)
            idle()
            shot(t, name)
        }
    }

    private fun shot(a: Activity, name: String) {
        val root = a.window.decorView
        val w = a.resources.displayMetrics.widthPixels
        val h = a.resources.displayMetrics.heightPixels
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        val f = File(File("build/screenshots").apply { mkdirs() }, "$name.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
