package com.gpstelemetry.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gpstelemetry.core.Speech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Sintesi reale con la voce Paola sul dispositivo (motore nativo sherpa-onnx).
 * Richiede la voce installata: scaricata dall'app o copiata con
 *   adb push … /data/local/tmp && adb shell run-as com.gpstelemetry cp -r …
 */
@RunWith(AndroidJUnit4::class)
class PiperDeviceTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun paolaGeneraAudio() {
        assumeTrue("voce Paola non installata", PiperModel.isInstalled(ctx))
        val vits = com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig(
            model = PiperModel.modelFile(ctx).path,
            tokens = PiperModel.tokensFile(ctx).path,
            dataDir = PiperModel.dataDir(ctx).path,
            noiseScale = 0.5f, noiseScaleW = 0.333f, lengthScale = 1.0f,
        )
        val tts = com.k2fsa.sherpa.onnx.OfflineTts(
            null,
            com.k2fsa.sherpa.onnx.OfflineTtsConfig(
                model = com.k2fsa.sherpa.onnx.OfflineTtsModelConfig(vits = vits, numThreads = PiperSpeaker.threads(), provider = "cpu"),
            ),
        )
        assertEquals(22050, tts.sampleRate())
        tts.generate("Pronto.", 0, 1f)
        val t0 = System.currentTimeMillis()
        val audio = tts.generate(Speech.SAMPLE, 0, PiperSpeaker.SPEED)
        val ms = System.currentTimeMillis() - t0
        val seconds = audio.samples.size / audio.sampleRate.toDouble()
        Log.i("PiperDeviceTest", "Sintesi $ms ms per %.1f s di audio".format(seconds))
        assertTrue("audio troppo corto ($seconds s)", seconds > 3.0)
        assertTrue("audio muto", audio.samples.any { kotlin.math.abs(it) > 0.05f })
        audio.save(ctx.cacheDir.resolve("paola-device.wav").path)
        tts.release()
    }

    @Test
    fun speakerRiproduceLeFrasiInOrdine() {
        assumeTrue("voce Paola non installata", PiperModel.isInstalled(ctx))
        Settings.setVoiceEngine(ctx, Settings.VoiceEngine.NEURAL)
        val coach = VoiceCoach(ctx)
        assertTrue(coach.usesNeural())
        val speaker = PiperSpeaker.shared(ctx)
        val done = CountDownLatch(2)
        val order = mutableListOf<String>()
        val attrs = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).build()
        for (text in listOf(Speech.START, Speech.NEUTRAL_ENTER)) {
            speaker.speak(text, attrs, onStart = { synchronized(order) { order += text } }, onDone = { done.countDown() },
                onError = { throw AssertionError("errore di sintesi: $it") })
        }
        assertTrue("riproduzione non conclusa", done.await(60, TimeUnit.SECONDS))
        assertEquals(listOf(Speech.START, Speech.NEUTRAL_ENTER), order)
        coach.shutdown()
    }
}
