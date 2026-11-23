package com.gpstelemetry.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Voce neurale femminile italiana "Paola" (Piper, modello VITS) eseguita sul
 * telefono con sherpa-onnx: suono naturale, nessuna rete durante il giro.
 *
 * Il modello (≈67 MB) NON è nell'APK: si scarica una volta dalle
 * impostazioni (release ufficiali sherpa-onnx, impronta SHA-256 verificata)
 * e si estrae nella memoria privata dell'app.
 *
 * Licenza: dataset della voce CC0 (OHF-Voice/voice-datasets), modello Piper
 * addestrato a partire da hifi_tts (vedi MODEL_CARD incluso nel pacchetto).
 */
object PiperModel {
    const val NAME = "vits-piper-it_IT-paola-medium"
    const val URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$NAME.tar.bz2"
    const val SHA256 = "7541f75778afa164e44e34baaef63befad7698595df26a95ca944b63ef1a16b4"
    const val DOWNLOAD_BYTES = 67_221_173L
    private const val MARKER = ".installed"

    fun dir(ctx: Context) = File(ctx.filesDir, "voices/$NAME")
    fun modelFile(ctx: Context) = File(dir(ctx), "it_IT-paola-medium.onnx")
    fun tokensFile(ctx: Context) = File(dir(ctx), "tokens.txt")
    fun dataDir(ctx: Context) = File(dir(ctx), "espeak-ng-data")

    fun isInstalled(ctx: Context) = File(dir(ctx), MARKER).exists() && modelFile(ctx).exists()

    /** Spazio occupato dalla voce installata (byte). */
    fun installedBytes(ctx: Context): Long = dir(ctx).walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    fun delete(ctx: Context) {
        PiperSpeaker.releaseShared()
        dir(ctx).deleteRecursively()
    }

    /**
     * Estrae dal .tar.bz2 solo ciò che serve: modello, token e i dati di
     * pronuncia di espeak-ng senza i dizionari delle altre lingue.
     */
    fun extract(archive: InputStream, target: File) {
        TarArchiveInputStream(BZip2CompressorInputStream(archive.buffered())).use { tar ->
            while (true) {
                val e = tar.nextEntry ?: break
                val rel = e.name.substringAfter('/', "")
                if (rel.isEmpty() || !keep(rel)) continue
                val out = File(target, rel).canonicalFile
                // Protezione da percorsi malevoli ("../").
                require(out.path.startsWith(target.canonicalPath)) { "Percorso non valido: ${e.name}" }
                if (e.isDirectory) out.mkdirs()
                else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { tar.copyTo(it) }
                }
            }
        }
    }

    internal fun keep(rel: String): Boolean {
        if (!rel.startsWith("espeak-ng-data/")) return true
        val name = rel.substringAfterLast('/')
        return !name.endsWith("_dict") || name == "it_dict"
    }

    /**
     * Scarica, verifica ed estrae la voce (bloccante: da un thread in
     * background). [progress] riceve byte scaricati e totali.
     */
    fun download(ctx: Context, cancelled: AtomicBoolean, progress: (Long, Long) -> Unit) {
        val tmp = File(ctx.cacheDir, "$NAME.tar.bz2.part")
        try {
            var url = URL(URL)
            var conn: HttpURLConnection
            // Le release GitHub reindirizzano a un altro host: si seguono i redirect.
            var hops = 0
            while (true) {
                conn = url.openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                val code = conn.responseCode
                if (code in 300..399 && hops++ < 5) {
                    url = URL(url, conn.getHeaderField("Location"))
                    conn.disconnect()
                    continue
                }
                if (code != 200) error("HTTP $code")
                break
            }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: DOWNLOAD_BYTES
            val digest = MessageDigest.getInstance("SHA-256")
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastReport = 0L
                    while (true) {
                        if (cancelled.get()) error("Download annullato")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        if (done - lastReport > 256 * 1024) {
                            lastReport = done
                            progress(done, total)
                        }
                    }
                    progress(done, total)
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            check(hash == SHA256) { "File della voce danneggiato (impronta non valida)" }

            val target = dir(ctx)
            val staging = File(target.parentFile, "$NAME.tmp").apply { deleteRecursively(); mkdirs() }
            tmp.inputStream().use { extract(it, staging) }
            target.deleteRecursively()
            check(staging.renameTo(target)) { "Impossibile installare la voce" }
            File(target, MARKER).writeText(SHA256)
        } finally {
            tmp.delete()
        }
    }
}

/**
 * Sintesi e riproduzione con la voce Piper: una frase alla volta, in ordine,
 * su un thread dedicato. L'audio è riprodotto mentre viene generato
 * (a frasi), quindi la voce parte prima della fine della sintesi.
 */
class PiperSpeaker private constructor(private val app: Context) {

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "piper-voice") }
    /** Aumenta a ogni interruzione: le frasi accodate prima vengono scartate. */
    private val epoch = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var tts: com.k2fsa.sherpa.onnx.OfflineTts? = null
    @Volatile private var track: AudioTrack? = null

    /** Carica il modello (≈1–3 s la prima volta): da chiamare in anticipo. */
    fun prepare() {
        worker.execute { runCatching { engine() }.onFailure { Log.w(TAG, "Piper non disponibile", it) } }
    }

    private fun engine(): com.k2fsa.sherpa.onnx.OfflineTts {
        tts?.let { return it }
        val vits = com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig(
            model = PiperModel.modelFile(app).path,
            tokens = PiperModel.tokensFile(app).path,
            dataDir = PiperModel.dataDir(app).path,
            // Impostazioni consigliate dal MODEL_CARD della voce.
            noiseScale = 0.5f,
            noiseScaleW = 0.333f,
            lengthScale = 1.0f,
        )
        val config = com.k2fsa.sherpa.onnx.OfflineTtsConfig(
            model = com.k2fsa.sherpa.onnx.OfflineTtsModelConfig(vits = vits, numThreads = threads(), provider = "cpu"),
        )
        val t = com.k2fsa.sherpa.onnx.OfflineTts(null, config)
        // Riscaldamento: la prima sintesi è più lenta.
        runCatching { t.generate("Pronto.", 0, 1.0f) }
        tts = t
        return t
    }

    /**
     * Accoda [text]. [onStart]/[onDone] incorniciano la riproduzione (focus
     * audio); [onError] se la voce non funziona (si ripiega sul sistema).
     */
    fun speak(text: String, attributes: AudioAttributes, onStart: () -> Unit, onDone: () -> Unit, onError: (String) -> Unit) {
        val mine = epoch.get()
        worker.execute {
            if (epoch.get() != mine) return@execute
            fun stopped() = epoch.get() != mine
            try {
                val t = engine()
                onStart()
                val rate = t.sampleRate()
                val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
                val at = AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(maxOf(min, rate * 4 / 2)) // ~0,5 s
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                track = at
                at.play()
                var written = 0
                val t0 = System.currentTimeMillis()
                // Oggetto esplicito, non una lambda: il codice nativo cerca il
                // metodo invoke([F)Integer sulla classe del callback, che le
                // lambda compilate con invokedynamic non hanno.
                val callback = object : (FloatArray) -> Int {
                    override fun invoke(chunk: FloatArray): Int {
                        if (stopped()) return 0
                        at.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
                        written += chunk.size
                        return 1
                    }
                }
                t.generateWithCallback(text, 0, SPEED, callback)
                Log.i(TAG, "Sintesi: ${System.currentTimeMillis() - t0} ms per ${written * 1000L / rate} ms di audio")
                // Attende la fine della riproduzione prima di liberare il focus.
                if (!stopped()) {
                    val end = written
                    val deadline = System.currentTimeMillis() + end * 1000L / rate + 1500
                    while (at.playbackHeadPosition < end && System.currentTimeMillis() < deadline && !stopped()) {
                        Thread.sleep(30)
                    }
                }
                at.stop()
                at.release()
                track = null
                onDone()
            } catch (e: Throwable) {
                Log.w(TAG, "Sintesi Piper non riuscita", e)
                track = null
                onDone()
                onError(text)
            }
        }
    }

    /** Interrompe la frase in corso e svuota la coda. */
    fun stopNow() {
        epoch.incrementAndGet()
        runCatching { track?.pause(); track?.flush() }
    }

    private fun close() {
        stopNow()
        worker.execute { runCatching { tts?.release() }; tts = null }
        worker.shutdown()
    }

    companion object {
        private const val TAG = "PiperSpeaker"
        /** Un filo più lenta del normale: si capisce meglio col casco. */
        const val SPEED = 0.95f

        /** Thread di sintesi: 2, come negli esempi Android di sherpa-onnx (più thread non accelerano). */
        fun threads(): Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 2)
        @Volatile private var shared: PiperSpeaker? = null

        fun shared(ctx: Context): PiperSpeaker =
            shared ?: synchronized(this) { shared ?: PiperSpeaker(ctx.applicationContext).also { shared = it } }

        /** Libera modello e thread (es. voce eliminata). */
        fun releaseShared() {
            synchronized(this) {
                shared?.close()
                shared = null
            }
        }
    }
}
