package com.gpstelemetry.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.gpstelemetry.core.Sectors

/**
 * Barra dei settori del giro: un segmento per settore (l'intera barra se il
 * tracciato non ha parziali). Ogni settore concluso si colora secondo il
 * confronto con lo storico:
 *  - viola  = miglior tempo di sempre;
 *  - verde  = meglio della media;
 *  - rosso  = peggio della media.
 * Il settore in corso mostra l'avanzamento rispetto al suo tempo medio.
 * Sotto ogni segmento: nome e tempo del settore.
 */
class SectorBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    data class Sector(
        val label: String,
        val timeMs: Long? = null,
        val rating: Sectors.Rating? = null,
    )

    private val density = resources.displayMetrics.density
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val pendingColor = ContextCompat.getColor(context, R.color.sector_pending)
    private val textPrimary = ContextCompat.getColor(context, R.color.text_primary)
    private val textSecondary = ContextCompat.getColor(context, R.color.text_secondary)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.1f
    }
    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        fontFeatureSettings = "tnum"
    }

    private var sectors: List<Sector> = listOf(Sector("S1"))
    private var current = -1
    private var progress = 0f
    private val rect = RectF()

    /** Altezza della barra (dp); il resto è per le etichette. */
    var barHeightDp = 14f
        set(value) { field = value; requestLayout(); invalidate() }

    /** Dimensione del testo del tempo di settore (sp). */
    var timeTextSp = 15f
        set(value) { field = value; requestLayout(); invalidate() }

    fun setSectors(list: List<Sector>) {
        sectors = list.ifEmpty { listOf(Sector("S1")) }
        invalidate()
    }

    /** Settore in corso e avanzamento 0..1 (stima rispetto al tempo medio). */
    fun setCurrent(index: Int, progress: Float) {
        current = index
        this.progress = progress.coerceIn(0f, 1f)
        invalidate()
    }

    private fun labelSize() = sp(10f)
    private fun timeSize() = sp(timeTextSp)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = (barHeightDp * density + 6 * density + labelSize() * 1.3f + timeSize() * 1.25f).toInt() +
            paddingTop + paddingBottom
        setMeasuredDimension(
            resolveSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(h, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val n = sectors.size
        val gap = 5 * density
        val left = paddingLeft.toFloat()
        val total = width - paddingLeft - paddingRight - gap * (n - 1)
        val segW = total / n
        val top = paddingTop.toFloat()
        val barH = barHeightDp * density
        val radius = barH / 2

        labelPaint.textSize = labelSize()
        timePaint.textSize = timeSize()

        for (i in 0 until n) {
            val s = sectors[i]
            val x0 = left + i * (segW + gap)
            rect.set(x0, top, x0 + segW, top + barH)

            fill.color = pendingColor
            canvas.drawRoundRect(rect, radius, radius, fill)

            if (s.rating != null || s.timeMs != null) {
                fill.color = TrackShape.ratingColor(context, s.rating)
                canvas.drawRoundRect(rect, radius, radius, fill)
            } else if (i == current) {
                // Settore in corso: riempimento proporzionale + bordo.
                if (progress > 0f) {
                    fill.color = ColorUtils.setAlphaComponent(textPrimary, 90)
                    val r2 = RectF(rect.left, rect.top, rect.left + segW * progress, rect.bottom)
                    canvas.drawRoundRect(r2, radius, radius, fill)
                }
                stroke.color = textPrimary
                canvas.drawRoundRect(rect, radius, radius, stroke)
            }

            // Etichette.
            val cx = x0 + segW / 2
            val labelY = top + barH + 6 * density + labelSize()
            labelPaint.color = if (i == current) textPrimary else textSecondary
            labelPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(ellipsize(s.label, labelPaint, segW), cx, labelY, labelPaint)

            val timeY = labelY + timeSize() * 1.15f
            timePaint.textAlign = Paint.Align.CENTER
            timePaint.color = if (s.rating != null) TrackShape.ratingColor(context, s.rating) else textSecondary
            val txt = s.timeMs?.let { Fmt.time(it) } ?: "—"
            canvas.drawText(ellipsize(txt, timePaint, segW), cx, timeY, timePaint)
        }
    }

    private fun ellipsize(text: String, p: Paint, maxW: Float): String {
        if (p.measureText(text) <= maxW) return text
        var t = text
        while (t.length > 1 && p.measureText("$t…") > maxW) t = t.dropLast(1)
        return "$t…"
    }
}
