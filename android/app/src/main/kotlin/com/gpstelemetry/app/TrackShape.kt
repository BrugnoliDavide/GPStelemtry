package com.gpstelemetry.app

import android.content.Context
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import androidx.core.content.ContextCompat
import com.gpstelemetry.core.Geo
import com.gpstelemetry.core.LatLon
import com.gpstelemetry.core.PathPoint
import com.gpstelemetry.core.Sectors
import com.gpstelemetry.core.Track
import com.gpstelemetry.core.TrackType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Proiezione di coordinate geografiche in un rettangolo di disegno,
 * mantenendo le proporzioni (equirettangolare locale, nord in alto).
 */
class Projection(points: List<LatLon>, private val box: RectF) {
    private val kx: Double
    private val minX: Double
    private val maxY: Double
    private val scale: Double
    private val offX: Float
    private val offY: Float

    init {
        val meanLat = if (points.isEmpty()) 0.0 else points.sumOf { it.lat } / points.size
        kx = cos(meanLat * PI / 180)
        val xs = points.map { it.lon * kx }
        val ys = points.map { it.lat }
        minX = xs.minOrNull() ?: 0.0
        val maxX = xs.maxOrNull() ?: 0.0
        val minY = ys.minOrNull() ?: 0.0
        maxY = ys.maxOrNull() ?: 0.0
        val spanX = max(maxX - minX, 1e-9)
        val spanY = max(maxY - minY, 1e-9)
        scale = min(box.width() / spanX, box.height() / spanY)
        offX = box.left + ((box.width() - spanX * scale) / 2).toFloat()
        offY = box.top + ((box.height() - spanY * scale) / 2).toFloat()
    }

    fun map(lat: Double, lon: Double): PointF = PointF(
        offX + ((lon * kx - minX) * scale).toFloat(),
        offY + ((maxY - lat) * scale).toFloat(),
    )

    fun path(points: List<LatLon>): Path {
        val p = Path()
        points.forEachIndexed { i, ll ->
            val pt = map(ll.lat, ll.lon)
            if (i == 0) p.moveTo(pt.x, pt.y) else p.lineTo(pt.x, pt.y)
        }
        return p
    }
}

/** Utilità per la rappresentazione stilizzata dei tracciati. */
object TrackShape {

    /**
     * Linea che rappresenta il tracciato: il percorso registrato (semplificato)
     * oppure, in sua assenza, la spezzata partenza → parziali → traguardo.
     */
    fun outline(track: Track): List<LatLon> {
        if (track.path.size >= 2) {
            val simplified = if (track.path.size > 400) Geo.simplify(track.path, 2.0) else track.path
            val pts = simplified.map { LatLon(it.lat, it.lon) }.toMutableList()
            if (track.type == TrackType.CIRCULAR && pts.size > 2 &&
                Geo.haversine(pts.first(), pts.last()) < 3 * track.tolerance
            ) {
                pts.add(pts.first()) // chiude il circuito
            }
            return pts
        }
        val pts = mutableListOf(track.start.center)
        track.splits.forEach { pts.add(it.gate.center) }
        pts.add(track.effectiveFinish.center)
        return pts
    }

    fun outlinePath(path: List<PathPoint>): List<LatLon> = path.map { LatLon(it.lat, it.lon) }

    /** Colore associato alla valutazione di un tempo. */
    fun ratingColor(ctx: Context, r: Sectors.Rating?): Int = ContextCompat.getColor(
        ctx,
        when (r) {
            Sectors.Rating.BEST -> R.color.sector_best
            Sectors.Rating.BETTER -> R.color.sector_better
            Sectors.Rating.WORSE -> R.color.sector_worse
            null -> R.color.sector_pending
        },
    )
}
