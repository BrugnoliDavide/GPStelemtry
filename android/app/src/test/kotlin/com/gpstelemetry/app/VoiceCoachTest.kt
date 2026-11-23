package com.gpstelemetry.app

import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.test.core.app.ApplicationProvider
import com.gpstelemetry.core.Speech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowTextToSpeech
import java.util.Locale

/** Voce al pilota: scelta della voce femminile e lettura delle frasi. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceCoachTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun voice(name: String, features: Set<String> = emptySet()) =
        Voice(name, Locale.ITALY, Voice.QUALITY_HIGH, Voice.LATENCY_NORMAL, false, features)

    @Test
    fun riconosceLeVociFemminili() {
        assertTrue(VoiceCoach.isFemale(voice("it-it-x-itb-local")))
        assertTrue(VoiceCoach.isFemale(voice("it-it-x-kda-network")))
        assertFalse(VoiceCoach.isFemale(voice("it-it-x-itc-local")))
        assertTrue(VoiceCoach.isFemale(voice("it-IT-SMTf00")))
        assertTrue(VoiceCoach.isFemale(voice("acme-voice", setOf("gender=female"))))
        assertFalse(VoiceCoach.isFemale(voice("acme-male")))
    }

    private fun shadowTts(coach: VoiceCoach): ShadowTextToSpeech {
        val tts = coach.javaClass.getDeclaredField("tts").apply { isAccessible = true }.get(coach) as TextToSpeech
        return Shadow.extract(tts)
    }

    @Test
    fun leggeLeFrasiQuandoIlMotoreEPronto() {
        ShadowTextToSpeech.addLanguageAvailability(Locale.ITALY)
        val coach = VoiceCoach(ctx)
        // Prima dell'inizializzazione la frase resta in coda…
        coach.speak(Speech.START)
        assertFalse(coach.ready)
        // …e viene letta appena il motore risponde.
        shadowTts(coach).onInitListener.onInit(TextToSpeech.SUCCESS)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(coach.ready)
        assertEquals(Speech.START, shadowTts(coach).lastSpokenText)
        coach.speak(Speech.SAMPLE)
        assertEquals(Speech.SAMPLE, shadowTts(coach).lastSpokenText)
        assertEquals(TextToSpeech.QUEUE_ADD, shadowTts(coach).queueMode)
        coach.shutdown()
    }
}
