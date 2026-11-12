package com.gpstelemetry.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.gpstelemetry.core.Geo
import com.gpstelemetry.core.GpsSample
import com.gpstelemetry.core.LatLon
import com.gpstelemetry.core.Sectors
import com.gpstelemetry.core.Session
import com.gpstelemetry.core.Track
import com.gpstelemetry.core.TrackType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min

/**
 * Immagine da condividere a fine giro (1080×1350, formato 4:5 dei social):
 * nome del tracciato, traiettoria stilizzata colorata per settore, tempo
 * del giro con confronto col record, tempi dei settori e telemetria.
 * Sempre in stile scuro, indipendentemente dal tema dell'app.
 */
object ShareCard {

    const val W = 1080
    const val H = 1350

    private const val BG = Color.BLACK
    private const val WHITE = Color.WHITE
    private val GRAY = Color.rgb(161, 161, 166)
    private val DIM = Color.argb(60, 255, 255, 255)
    private val ACCENT = Color.rgb(255, 106, 0)
    private val PURPLE = Color.rgb(176, 38, 255)
    private val GREEN = Color.rgb(0, 230, 118)
    private val RED = Color.rgb(255, 45, 45)

    private val black = Typeface.create("sans-serif-black", Typeface.NORMAL)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val condensed = Typeface.create("sans-serif-condensed", Typeface.BOLD)

    fun ratingColor(r: Sectors.Rating?) = when (r) {
        Sectors.Rating.BEST -> PURPLE
        Sectors.Rating.BETTER -> GREEN
        Sectors.Rating.WORSE -> RED
        null -> WHITE
    }

    fun render(session: Session, track: Track?, bench: Sectors.Benchmark): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        // Sfondo nero con bagliori.
        c.drawColor(BG)
        p.shader = RadialGradient(W * 1f, 0f, W * 0.9f, Color.argb(90, 255, 106, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), p)
        p.shader = RadialGradient(0f, H * 1f, W * 1f, Color.argb(70, 139, 44, 245), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), p)
        p.shader = null

        val result = session.result
        val total = result?.elapsedMs ?: 0L
        val splitTimes = result?.splits ?: emptyList()
        val nSectors = Sectors.count(session.trackSplits.size)
        val durations = Sectors.durations(splitTimes, total)
        val ratings = durations.mapIndexed { i, d -> Sectors.rate(d, bench.sectors.getOrNull(i)) }
        val lapRating = Sectors.rate(total, bench.lap)

        // Intestazione.
        text(c, p, "GPS TELEMETRY", 60f, 92f, 26f, medium, ACCENT, spacing = 0.25f)
        val date = SimpleDateFormat("dd MMM yyyy · HH:mm", Locale.ITALY).format(Date(session.startedAt))
        text(c, p, date.uppercase(Locale.ITALY), W - 60f, 92f, 26f, medium, GRAY, align = Paint.Align.RIGHT, spacing = 0.1f)

        val name = track?.name ?: session.name.substringBefore(" — ")
        text(c, p, fit(p, name, 960f, 76f, black), 60f, 185f, 76f, black, WHITE)

        val lapSamples = lapSamples(session)
        val dist = lapSamples.zipWithNext { a, b -> Geo.haversine(a.lat, a.lon, b.lat, b.lon) }.sum()
        val typeStr = if (session.trackType == TrackType.CIRCULAR) "CIRCUITO" else "APERTO"
        val sub = listOfNotNull(
            typeStr,
            if (dist > 0) Fmt.dist(dist).uppercase(Locale.ITALY) else null,
            if (nSectors > 1) "$nSectors SETTORI" else null,
        ).joinToString("  ·  ")
        text(c, p, sub, 60f, 240f, 28f, medium, GRAY, spacing = 0.12f)

        // Pannello in vetro con la traiettoria.
        val panel = RectF(60f, 285f, W - 60f, 765f)
        p.style = Paint.Style.FILL
        p.color = Color.argb(22, 255, 255, 255)
        c.drawRoundRect(panel, 48f, 48f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2f
        p.color = Color.argb(46, 255, 255, 255)
        c.drawRoundRect(panel, 48f, 48f, p)
        drawTrajectory(c, session, track, lapSamples, ratings, RectF(panel.left + 60, panel.top + 50, panel.right - 60, panel.bottom - 50))

        // Tempo del giro.
        p.style = Paint.Style.FILL
        val timeStr = if (total > 0) Fmt.time(total) else "—"
        p.fontFeatureSettings = "tnum"
        text(c, p, timeStr, W / 2f, 965f, 200f, condensed, ratingColor(lapRating), align = Paint.Align.CENTER)
        p.fontFeatureSettings = null
        val lapRef = bench.lap
        val deltaStr = when {
            lapRef == null -> "RECORD · PRIMO GIRO"
            total < lapRef.best -> "NUOVO RECORD  ${signed(total - lapRef.best)}"
            else -> "${signed(total - lapRef.best)}  DAL RECORD"
        }
        text(c, p, deltaStr, W / 2f, 1030f, 34f, condensed, ratingColor(lapRating), align = Paint.Align.CENTER, spacing = 0.08f)

        // Settori.
        drawSectors(c, p, durations, ratings, bench, nSectors, RectF(60f, 1080f, W - 60f, 1250f))

        // Piè di pagina: telemetria.
        val vmax = lapSamples.mapNotNull { it.speed }.maxOrNull()?.let { it * 3.6f }
        val hrLap = session.hr.filter { h ->
            val st = result?.startTime; val ft = result?.finishTime
            st != null && ft != null && h.t in st..ft
        }.map { it.bpm }
        val foot = listOfNotNull(
            vmax?.let { String.format(Locale.US, "V MAX %.0f KM/H", it) },
            hrLap.takeIf { it.isNotEmpty() }?.let { "♥ ${it.average().toInt()} BPM MEDI" },
            result?.let {
                if (it.offTrackEpisodes == 0) "SEMPRE IN TRACCIATO"
                else String.format(Locale.US, "FUORI %.1f S (%d)", it.offTrackMs / 1000.0, it.offTrackEpisodes)
            },
        ).joinToString("   ·   ")
        text(c, p, foot, W / 2f, 1300f, 26f, medium, GRAY, align = Paint.Align.CENTER, spacing = 0.1f)
        return bmp
    }

    /** Campioni GPS tra partenza e arrivo (tutta la sessione se il giro manca). */
    private fun lapSamples(s: Session): List<GpsSample> {
        val st = s.result?.startTime
        val ft = s.result?.finishTime
        if (st != null && ft != null) {
            val lap = s.samples.filter { it.t in st..ft }
            if (lap.size >= 2) return lap
        }
        return s.samples
    }

    private fun drawTrajectory(
        c: Canvas,
        session: Session,
        track: Track?,
        samples: List<GpsSample>,
        ratings: List<Sectors.Rating>,
        box: RectF,
    ) {
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        if (samples.size < 2) {
            // Nessuna traccia registrata: forma del tracciato.
            val outline = track?.let { TrackShape.outline(it) } ?: return
            if (outline.size < 2) return
            val proj = Projection(outline, box)
            val path = proj.path(outline)
            stroke.color = Color.argb(80, 255, 106, 0); stroke.strokeWidth = 30f
            c.drawPath(path, stroke)
            stroke.color = WHITE; stroke.strokeWidth = 10f
            c.drawPath(path, stroke)
            return
        }

        val pts = samples.map { LatLon(it.lat, it.lon) }
        val proj = Projection(pts, box)
        val splitT = session.result?.splits?.map { it.t } ?: emptyList()
        val hasLap = session.result?.finishTime != null

        // Un tracciato per settore, colorato secondo la valutazione.
        val paths = mutableMapOf<Int, Path>()
        for (i in 0 until samples.size - 1) {
            val a = samples[i]
            val b = samples[i + 1]
            val sector = splitT.count { it <= a.t }
            val path = paths.getOrPut(sector) {
                Path().also { val pa = proj.map(a.lat, a.lon); it.moveTo(pa.x, pa.y) }
            }
            val pb = proj.map(b.lat, b.lon)
            path.lineTo(pb.x, pb.y)
        }
        paths.forEach { (sector, path) ->
            val col = if (hasLap) ratingColor(ratings.getOrNull(sector)) else WHITE
            stroke.color = Color.argb(70, Color.red(col), Color.green(col), Color.blue(col))
            stroke.strokeWidth = 34f
            c.drawPath(path, stroke)
        }
        paths.forEach { (sector, path) ->
            stroke.color = if (hasLap) ratingColor(ratings.getOrNull(sector)) else WHITE
            stroke.strokeWidth = 11f
            c.drawPath(path, stroke)
        }

        // Partenza.
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val s0 = proj.map(samples.first().lat, samples.first().lon)
        fill.color = ACCENT
        c.drawCircle(s0.x, s0.y, 22f, fill)
        fill.color = BG
        c.drawCircle(s0.x, s0.y, 10f, fill)
        // Parziali: punto bianco dove sono stati attraversati.
        fill.color = WHITE
        session.result?.splits?.forEach { sp ->
            val near = samples.minByOrNull { abs(it.t - sp.t) } ?: return@forEach
            val pt = proj.map(near.lat, near.lon)
            c.drawCircle(pt.x, pt.y, 9f, fill)
        }
    }

    private fun drawSectors(
        c: Canvas,
        p: Paint,
        durations: List<Long>,
        ratings: List<Sectors.Rating>,
        bench: Sectors.Benchmark,
        n: Int,
        area: RectF,
    ) {
        val perRow = min(n, 4)
        val rows = ceil(n / perRow.toDouble()).toInt()
        val gap = 24f
        val colW = (area.width() - gap * (perRow - 1)) / perRow
        val rowH = area.height() / rows
        val scale = if (rows > 1) 0.75f else 1f
        for (i in 0 until n) {
            val r = i / perRow
            val col = i % perRow
            val x = area.left + col * (colW + gap)
            val y = area.top + r * rowH
            val d = durations.getOrNull(i)
            val color = if (d != null) ratingColor(ratings.getOrNull(i)) else DIM

            p.style = Paint.Style.FILL
            p.color = color
            c.drawRoundRect(RectF(x, y, x + colW, y + 14f), 7f, 7f, p)

            val label = if (n == 1) "GIRO" else "S${i + 1}"
            text(c, p, label, x, y + 14f + 40f * scale, 26f * scale, medium, GRAY, spacing = 0.15f)
            p.fontFeatureSettings = "tnum"
            text(c, p, d?.let { Fmt.time(it) } ?: "—", x, y + 14f + 98f * scale, 52f * scale, condensed, if (d != null) color else GRAY)
            p.fontFeatureSettings = null
            val ref = bench.sectors.getOrNull(i)
            val delta = d?.let { Sectors.deltaToBest(it, ref) }
            val sub = when {
                d == null -> ""
                delta == null -> "RECORD"
                delta < 0 -> "RECORD ${signed(delta)}"
                else -> signed(delta)
            }
            if (rows == 1) text(c, p, sub, x, y + 14f + 140f, 26f, condensed, GRAY, spacing = 0.05f)
        }
    }

    private fun text(
        c: Canvas,
        p: Paint,
        s: String,
        x: Float,
        y: Float,
        size: Float,
        tf: Typeface,
        color: Int,
        align: Paint.Align = Paint.Align.LEFT,
        spacing: Float = 0f,
    ) {
        p.style = Paint.Style.FILL
        p.shader = null
        p.textSize = size
        p.typeface = tf
        p.color = color
        p.textAlign = align
        p.letterSpacing = spacing
        c.drawText(s, x, y, p)
        p.letterSpacing = 0f
    }

    /** Accorcia il testo con "…" finché sta nella larghezza indicata. */
    private fun fit(p: Paint, s: String, maxW: Float, size: Float, tf: Typeface): String {
        p.textSize = size
        p.typeface = tf
        if (p.measureText(s) <= maxW) return s
        var t = s
        while (t.length > 1 && p.measureText("$t…") > maxW) t = t.dropLast(1)
        return "$t…"
    }

    private fun signed(ms: Long): String {
        val s = String.format(Locale.US, "%.2f", abs(ms) / 1000.0)
        return if (ms < 0) "−$s" else "+$s"
    }
}
