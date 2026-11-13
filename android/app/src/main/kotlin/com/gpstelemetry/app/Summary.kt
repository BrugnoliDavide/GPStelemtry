package com.gpstelemetry.app

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.gpstelemetry.core.LapStats
import java.util.Locale

/**
 * Resoconto del giro in riquadri (velocità massima e media, accelerazione e
 * inclinazione massime, dislivello, distanza, battito medio) e avviso delle
 * uscite dal tracciato. Usato dal dettaglio sessione e dal cronometro a fine
 * giro.
 */
object Summary {

    data class Tile(val label: String, val value: String)

    fun tiles(ctx: Context, s: LapStats, compact: Boolean = false): List<Tile> {
        fun f(fmt: String, v: Double) = String.format(Locale.US, fmt, v)
        val list = mutableListOf(
            Tile(ctx.getString(R.string.sum_vmax), s.maxSpeedKmh?.let { Units.speed(it) } ?: "—"),
            Tile(ctx.getString(R.string.sum_vavg), s.avgSpeedKmh?.let { Units.speed(it) } ?: "—"),
            Tile(ctx.getString(R.string.sum_accel), s.maxAccelG?.let { f("%.2f g", it) } ?: "—"),
            Tile(ctx.getString(R.string.sum_incline), s.maxInclineDeg?.let { f("%.0f°", it) } ?: "—"),
            Tile(ctx.getString(R.string.sum_elev), elevation(s)),
        )
        if (!compact) {
            list += Tile(ctx.getString(R.string.sum_distance), if (s.distanceM > 0) Fmt.dist(s.distanceM) else "—")
            s.avgHr?.let { list += Tile(ctx.getString(R.string.sum_hr), "$it bpm") }
        }
        return list
    }

    /** "+32 / −12 m" o "+105 / −39 ft" ("—" se la quota GPS non è disponibile). */
    fun elevation(s: LapStats): String {
        val g = s.elevGainM ?: return "—"
        return Units.elevation(g, s.elevLossM ?: 0.0)
    }

    /** Riempie la griglia con i riquadri del resoconto. */
    fun fill(grid: GridLayout, tiles: List<Tile>, columns: Int, valueSp: Float = 18f) {
        val ctx = grid.context
        grid.removeAllViews()
        grid.columnCount = columns
        val pad = (10 * ctx.resources.displayMetrics.density).toInt()
        tiles.forEach { t ->
            val cell = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(4, pad, 4, pad)
            }
            cell.addView(TextView(ctx).apply {
                text = t.label
                setTextAppearance(R.style.Text_Label)
                textSize = 9.5f
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            cell.addView(TextView(ctx).apply {
                text = t.value
                textSize = valueSp
                typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
                fontFeatureSettings = "tnum"
                setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
                gravity = Gravity.CENTER
                maxLines = 1
            })
            grid.addView(cell, GridLayout.LayoutParams().apply {
                width = 0
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            })
        }
    }

    /** Avviso delle uscite dal tracciato: rosso se ce ne sono, verde altrimenti. */
    fun bindAlert(tv: TextView, s: LapStats) {
        val ctx = tv.context
        if (s.offTrackEpisodes > 0) {
            tv.text = ctx.resources.getQuantityString(
                R.plurals.sum_offtrack_alert, s.offTrackEpisodes, s.offTrackEpisodes,
                String.format(Locale.US, "%.1f s", s.offTrackMs / 1000.0),
            )
            tv.setBackgroundResource(R.drawable.bg_offtrack)
            tv.setTextColor(ContextCompat.getColor(ctx, android.R.color.white))
        } else {
            tv.setText(R.string.sum_ontrack)
            tv.setBackgroundResource(R.drawable.bg_ontrack)
            tv.setTextColor(ContextCompat.getColor(ctx, R.color.on_accent))
        }
    }
}
