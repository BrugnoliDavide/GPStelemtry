package com.gpstelemetry.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.gpstelemetry.core.LatLon
import com.gpstelemetry.core.Track

/**
 * Anteprima stilizzata della forma di un tracciato: linea con bagliore
 * arancione, punto di partenza e segni dei parziali. Opzionalmente mostra
 * la posizione corrente (cronometro).
 */
class TrackShapeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val accent = ContextCompat.getColor(context, R.color.accent)
    private val lineColor = ContextCompat.getColor(context, R.color.track_line)

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = ColorUtils.setAlphaComponent(accent, 90)
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = lineColor
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var track: Track? = null
    private var outline: List<LatLon> = emptyList()
    private var marker: LatLon? = null
    private var cachedPath: Path? = null
    private var projection: Projection? = null

    /** Spessore della linea in dp (le anteprime piccole usano linee sottili). */
    var lineWidthDp: Float = 2.5f
        set(value) { field = value; invalidate() }

    fun setTrack(t: Track?) {
        track = t
        outline = t?.let { TrackShape.outline(it) } ?: emptyList()
        cachedPath = null
        invalidate()
    }

    /** Posizione corrente da evidenziare (null per nasconderla). */
    fun setMarker(p: LatLon?) {
        marker = p
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        cachedPath = null
    }

    override fun onDraw(canvas: Canvas) {
        val t = track ?: return
        if (outline.size < 2) return
        val pad = 10 * density
        if (cachedPath == null) {
            val box = RectF(pad, pad, width - pad, height - pad)
            projection = Projection(outline, box).also { cachedPath = it.path(outline) }
        }
        val proj = projection ?: return
        val path = cachedPath ?: return

        val lw = lineWidthDp * density
        glowPaint.strokeWidth = lw * 3.2f
        linePaint.strokeWidth = lw
        canvas.drawPath(path, glowPaint)
        canvas.drawPath(path, linePaint)

        // Parziali: piccoli punti arancioni.
        dotPaint.color = accent
        t.splits.forEach {
            val p = proj.map(it.gate.lat, it.gate.lon)
            canvas.drawCircle(p.x, p.y, lw * 1.1f, dotPaint)
        }
        // Partenza: punto arancione con alone; traguardo (aperto): punto pieno.
        val s = proj.map(t.start.lat, t.start.lon)
        dotPaint.color = ColorUtils.setAlphaComponent(accent, 80)
        canvas.drawCircle(s.x, s.y, lw * 3.2f, dotPaint)
        dotPaint.color = accent
        canvas.drawCircle(s.x, s.y, lw * 1.7f, dotPaint)
        t.finish?.takeIf { t.effectiveFinish !== t.start }?.let {
            val f = proj.map(it.lat, it.lon)
            dotPaint.color = lineColor
            canvas.drawCircle(f.x, f.y, lw * 1.7f, dotPaint)
        }

        marker?.let {
            val m = proj.map(it.lat, it.lon)
            dotPaint.color = ColorUtils.setAlphaComponent(Color.WHITE, 70)
            canvas.drawCircle(m.x, m.y, lw * 4f, dotPaint)
            dotPaint.color = ContextCompat.getColor(context, R.color.sector_better)
            canvas.drawCircle(m.x, m.y, lw * 2f, dotPaint)
        }
    }
}
