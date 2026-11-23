package com.gpstelemetry.app

import android.app.Activity
import android.graphics.Typeface
import android.net.Uri
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.gpstelemetry.core.ExternalRecording
import com.gpstelemetry.core.Session
import com.gpstelemetry.core.SessionStatus
import com.gpstelemetry.core.Track
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * "Inserisci registrazioni da altro device": un file GPS registrato con un
 * altro dispositivo (Insta360, smartwatch…) viene verificato sul tracciato
 * e i giri completi vengono salvati come sessioni, come se fossero stati
 * cronometrati dall'app.
 */
object ExternalImportUi {

    private const val MAX_BYTES = 60L * 1024 * 1024

    /** Formati proposti dal selettore di file (molti telefoni non conoscono GPX/FIT: anche "qualsiasi"). */
    val MIME_TYPES = arrayOf("application/gpx+xml", "application/vnd.garmin.tcx+xml", "application/vnd.ant.fit", "application/octet-stream", "text/xml", "application/xml", "text/plain", "*/*")

    fun explain(a: Activity, track: Track, pick: () -> Unit) {
        if (track.path.size < 2) {
            MaterialAlertDialogBuilder(a)
                .setTitle(R.string.ext_title)
                .setMessage(R.string.ext_no_path)
                .setPositiveButton(R.string.close, null)
                .show()
            return
        }
        MaterialAlertDialogBuilder(a)
            .setTitle(R.string.ext_title)
            .setMessage(a.getString(R.string.ext_explain, (ExternalRecording.MIN_INSIDE * 100).toInt()))
            .setPositiveButton(R.string.ext_choose) { _, _ -> pick() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Legge e verifica il file in background, poi mostra i giri trovati. */
    fun import(a: Activity, track: Track, uri: Uri, onSaved: () -> Unit) {
        val ctx = a.applicationContext
        val progress = MaterialAlertDialogBuilder(a)
            .setTitle(R.string.ext_title)
            .setMessage(R.string.ext_reading)
            .setCancelable(false)
            .show()
        thread(name = "ext-import") {
            val r = runCatching {
                val bytes = ctx.contentResolver.openInputStream(uri)?.use { input ->
                    val buf = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(chunk)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) throw ExternalRecording.ImportException(ctx.getString(R.string.ext_too_big))
                        buf.write(chunk, 0, n)
                    }
                    buf.toByteArray()
                } ?: throw ExternalRecording.ImportException(ctx.getString(R.string.ext_unreadable))
                ExternalRecording.analyze(track, ExternalRecording.parse(bytes))
            }
            a.runOnUiThread {
                if (a.isFinishing || a.isDestroyed) return@runOnUiThread
                progress.dismiss()
                r.onSuccess { showResult(a, track, it, onSaved) }.onFailure { e ->
                    MaterialAlertDialogBuilder(a)
                        .setTitle(R.string.ext_error_title)
                        .setMessage((e as? ExternalRecording.ImportException)?.message ?: a.getString(R.string.ext_unreadable))
                        .setPositiveButton(R.string.close, null)
                        .show()
                }
            }
        }
    }

    /** Riepilogo: giri trovati (il migliore in viola), quota dentro il tracciato, già importati. */
    internal fun showResult(a: Activity, track: Track, an: ExternalRecording.Analysis, onSaved: () -> Unit) {
        val existing = TrackStore.listSummaries(a).filter { it.trackId == track.id }.mapNotNull { it.result?.startTime }
        val fresh = an.laps.filterNot { ExternalRecording.isDuplicate(it, existing) }
        val dup = an.laps.size - fresh.size
        val best = an.best
        val d = a.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(4), dp(24), 0)
        }
        fun line(text: String, size: Float = 14f, color: Int = R.color.text_secondary) = TextView(a).apply {
            this.text = text
            textSize = size
            setTextColor(ContextCompat.getColor(a, color))
        }
        val first = an.recording.samples.first().t
        val date = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALY).format(Date(first))
        box.addView(line(a.getString(R.string.ext_source, sourceLabel(an.recording), date)))
        box.addView(line(a.getString(R.string.ext_inside, (an.inside * 100).toInt())).apply { setPadding(0, dp(2), 0, dp(10)) })
        an.laps.forEachIndexed { i, lap ->
            val isBest = lap === best && an.laps.size > 1
            val isDup = lap !in fresh
            box.addView(LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(40)
                addView(TextView(a).apply {
                    text = a.getString(R.string.ext_lap_n, i + 1) + if (isDup) " · " + a.getString(R.string.ext_already) else ""
                    textSize = 15f
                    setTextColor(ContextCompat.getColor(a, if (isDup) R.color.text_secondary else R.color.text_primary))
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                lap.result.offTrackEpisodes.takeIf { it > 0 }?.let {
                    addView(TextView(a).apply {
                        text = a.getString(R.string.ext_off, it)
                        textSize = 12f
                        setTextColor(ContextCompat.getColor(a, R.color.warn))
                        setPadding(0, 0, dp(10), 0)
                    })
                }
                addView(TextView(a).apply {
                    text = Fmt.time(lap.result.elapsedMs)
                    textSize = 17f
                    typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
                    fontFeatureSettings = "tnum"
                    setTextColor(ContextCompat.getColor(a, if (isBest) R.color.sector_best else R.color.text_primary))
                })
            })
        }
        if (dup > 0) box.addView(line(a.getString(R.string.ext_dup_hint, dup)).apply { setPadding(0, dp(8), 0, 0) })
        val b = MaterialAlertDialogBuilder(a)
            .setTitle(a.resources.getQuantityString(R.plurals.ext_found, an.laps.size, an.laps.size))
            .setView(ScrollView(a).apply { addView(box) })
            .setNegativeButton(R.string.cancel, null)
        if (fresh.isNotEmpty()) {
            b.setPositiveButton(a.resources.getQuantityString(R.plurals.ext_save, fresh.size, fresh.size)) { _, _ ->
                val n = save(a, track, an, fresh)
                Toast.makeText(a, a.resources.getQuantityString(R.plurals.ext_saved, n, n), Toast.LENGTH_LONG).show()
                onSaved()
            }
        }
        b.show()
    }

    /** Salva i giri come sessioni concluse (come quelle registrate dall'app). */
    private fun save(a: Activity, track: Track, an: ExternalRecording.Analysis, laps: List<ExternalRecording.Lap>): Int {
        val fmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALY)
        val (type, model) = Profile.vehicleForNewSession(a)
        val source = sourceLabel(an.recording)
        laps.forEach { lap ->
            val start = lap.result.startTime ?: lap.samples.first().t
            val s = Session(
                id = TrackStore.newId(),
                trackId = track.id,
                name = "${track.name} — ${fmt.format(Date(start))} · $source",
                startedAt = start,
                trackType = track.type,
                trackStart = track.start,
                trackFinish = track.finish,
                trackSplits = track.splits.toList(),
                samples = lap.samples.toMutableList(),
                hr = lap.hr.toMutableList(),
                result = lap.result,
                status = SessionStatus.FINISHED,
                vehicle = model,
                vehicleType = type,
            )
            TrackStore.saveSession(a, s)
            ResultPublisher.autoSubmit(a, s.id)
        }
        return laps.size
    }

    /** Nome breve del dispositivo: dal file se lo dichiara, altrimenti il formato. */
    internal fun sourceLabel(r: ExternalRecording.Recording): String {
        val c = r.creator?.lowercase().orEmpty()
        val known = listOf("insta360" to "Insta360", "garmin" to "Garmin", "apple" to "Apple Watch", "coros" to "Coros",
            "suunto" to "Suunto", "polar" to "Polar", "wahoo" to "Wahoo", "strava" to "Strava", "gopro" to "GoPro", "samsung" to "Samsung")
        return known.firstOrNull { it.first in c }?.second ?: r.creator?.take(24)?.takeIf { it.isNotBlank() } ?: r.format
    }
}
