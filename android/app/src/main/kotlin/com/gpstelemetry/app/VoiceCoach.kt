package com.gpstelemetry.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Voce al pilota: legge i tempi dei settori e del giro.
 *
 * Due motori:
 *  - NEURALE (predefinito se scaricato): voce femminile italiana "Paola"
 *    di Piper, eseguita sul telefono ([PiperSpeaker]) — naturale e offline;
 *  - SISTEMA: sintesi vocale di Android (TextToSpeech), usata se la voce
 *    neurale non è installata o in caso di errore.
 *
 * Perché la sintesi del sistema: funziona senza rete (voci "locali"), parla
 * subito, non costa nulla e non richiede file audio registrati. La voce è
 * italiana e, se disponibile, FEMMINILE: l'utente può sceglierla e
 * provarla nelle impostazioni; altrimenti la si sceglie in automatico.
 *
 * L'audio usa il canale "indicazioni di navigazione": passa
 * nell'interfono/auricolari Bluetooth come il navigatore e abbassa
 * temporaneamente la musica invece di interromperla.
 */
class VoiceCoach(ctx: Context, private val onReady: ((VoiceCoach) -> Unit)? = null) {

    private val app = ctx.applicationContext
    private val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .build()
    private val pending = AtomicInteger(0)
    private val queue = mutableListOf<String>()
    private var tts: TextToSpeech? = null

    /** Vero quando il motore è pronto e ha una voce italiana. */
    @Volatile var ready = false
        private set

    /** Vero se il motore non ha l'italiano installato. */
    @Volatile var missingLanguage = false
        private set

    init {
        tts = TextToSpeech(app) { status -> onInit(status) }
    }

    private fun onInit(status: Int) {
        val t = tts ?: return
        if (status != TextToSpeech.SUCCESS) return
        val lang = t.setLanguage(Locale.ITALY)
        if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
            missingLanguage = true
            onReady?.invoke(this)
            return
        }
        t.setAudioAttributes(attributes)
        t.setSpeechRate(0.95f) // un filo più lenta: si capisce meglio col casco
        chooseVoice(Settings.voiceName(app))
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) = Unit
            override fun onDone(id: String?) = release()
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) = release()
        })
        ready = true
        synchronized(queue) {
            queue.forEach { speakNow(it) }
            queue.clear()
        }
        onReady?.invoke(this)
    }

    private fun release() {
        if (pending.decrementAndGet() <= 0) {
            pending.set(0)
            audio.abandonAudioFocusRequest(focus)
        }
    }

    /** Voci italiane disponibili, femminili e locali per prime. */
    fun italianVoices(): List<Voice> {
        val voices = runCatching { tts?.voices }.getOrNull() ?: return emptyList()
        return voices
            .filter { it.locale.language == "it" }
            .sortedWith(
                compareByDescending<Voice> { isFemale(it) }
                    .thenBy { it.isNetworkConnectionRequired }
                    .thenByDescending { it.quality }
                    .thenBy { it.name }
            )
    }

    /** Imposta la voce [name] o, se assente, la migliore voce femminile italiana. */
    fun chooseVoice(name: String?) {
        val t = tts ?: return
        val list = italianVoices()
        val v = list.firstOrNull { it.name == name } ?: list.firstOrNull()
        if (v != null) runCatching { t.voice = v }
    }

    /** Voce in uso (nome tecnico del motore). */
    val currentVoice: String? get() = runCatching { tts?.voice?.name }.getOrNull()

    /** Vero se si usa la voce neurale (scelta e installata). */
    fun usesNeural(): Boolean =
        Settings.voiceEngine(app) == Settings.VoiceEngine.NEURAL && PiperModel.isInstalled(app)

    /** Carica in anticipo la voce neurale (es. all'avvio della sessione). */
    fun prepare() {
        if (usesNeural()) PiperSpeaker.shared(app).prepare()
    }

    private fun acquire() {
        if (pending.getAndIncrement() == 0) audio.requestAudioFocus(focus)
    }

    /** Legge [text] dopo quanto già in coda. */
    fun speak(text: String) {
        if (usesNeural()) {
            PiperSpeaker.shared(app).speak(
                text, attributes,
                onStart = { acquire() },
                onDone = { release() },
                onError = { failed -> speakSystem(failed) }, // ripiego sulla voce di sistema
            )
            return
        }
        speakSystem(text)
    }

    private fun speakSystem(text: String) {
        if (!ready) {
            synchronized(queue) { queue.add(text) }
            return
        }
        speakNow(text)
    }

    /** Interrompe la lettura in corso e legge subito [text] (prova voce). */
    fun speakImmediately(text: String) {
        tts?.stop()
        if (PiperModel.isInstalled(app)) PiperSpeaker.shared(app).stopNow()
        pending.set(0)
        speak(text)
    }

    private fun speakNow(text: String) {
        val t = tts ?: return
        acquire()
        t.speak(text, TextToSpeech.QUEUE_ADD, Bundle(), "gpstel-${System.nanoTime()}")
    }

    fun shutdown() {
        runCatching { tts?.stop(); tts?.shutdown() }
        tts = null
        ready = false
        audio.abandonAudioFocusRequest(focus)
    }

    companion object {
        @Volatile private var shared: VoiceCoach? = null

        /**
         * Istanza condivisa usata durante la registrazione: sopravvive al
         * servizio, così l'ultima frase (il giro) non viene troncata quando
         * la sessione si chiude.
         */
        fun shared(ctx: Context): VoiceCoach =
            shared ?: synchronized(this) { shared ?: VoiceCoach(ctx).also { shared = it } }

        /** Applica una nuova voce scelta nelle impostazioni all'istanza condivisa. */
        fun refreshShared(ctx: Context) {
            shared?.chooseVoice(Settings.voiceName(ctx))
        }

        /**
         * Le API di Android non dicono il genere della voce: lo si deduce dal
         * nome/caratteristiche dichiarate dai motori più diffusi.
         *  - Google: voci italiane "it-it-x-itb" e "it-it-x-kda" femminili,
         *    "itc" e "itd" maschili;
         *  - Samsung: "SMTf…" femminili, "SMTm…" maschili;
         *  - altri motori: "female"/"male" nel nome o nelle caratteristiche.
         */
        fun isFemale(v: Voice): Boolean {
            val n = v.name.lowercase(Locale.ROOT)
            val f = v.features.orEmpty().joinToString(" ").lowercase(Locale.ROOT)
            return when {
                "female" in n || "female" in f || "gender=f" in f -> true
                "male" in n || "male" in f -> false
                Regex("x-(itb|kda)").containsMatchIn(n) -> true
                Regex("x-(itc|itd)").containsMatchIn(n) -> false
                Regex("smtf\\d").containsMatchIn(n) -> true
                else -> false
            }
        }

        /** Nome leggibile della voce per le impostazioni. */
        fun label(v: Voice, index: Int): String {
            val gender = if (isFemale(v)) "femminile" else "voce"
            val net = if (v.isNetworkConnectionRequired) " · online" else ""
            return "Voce ${index + 1} ($gender$net)"
        }
    }
}
