package com.gpstelemetry.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Point
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import androidx.core.content.ContextCompat
import com.gpstelemetry.core.Gate
import com.gpstelemetry.core.Geo
import com.gpstelemetry.core.LatLon
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.math.hypot
import kotlin.math.max

/**
 * Livello della mappa che disegna le LINEE di cronometraggio (partenza,
 * traguardo, parziali) e ne permette la modifica diretta:
 *  - la linea è disegnata con la sua vera lunghezza e orientamento, spessa
 *    e contornata (a scacchi per partenza/traguardo) con un'etichetta;
 *  - il pallino centrale si TRASCINA per spostare la linea;
 *  - la maniglia circolare sull'asse della linea si trascina per RUOTARLA
 *    (resta sempre a distanza comoda dal centro, anche se la linea è corta);
 *  - un tocco seleziona la linea (l'editor mostra angolo e larghezza).
 */
class GateOverlay(context: Context, private val listener: Listener) : Overlay() {

    data class Item(
        val key: String,
        val label: String,
        val color: Int,
        val gate: Gate,
        /** Disegno a scacchi (partenza/traguardo). */
        val checkered: Boolean,
    )

    interface Listener {
        /** Durante il trascinamento (aggiornamento in tempo reale). */
        fun onGateChanging(key: String, gate: Gate)
        /** Fine del trascinamento. */
        fun onGateChanged(key: String, gate: Gate)
        fun onGateTapped(key: String)
    }

    private enum class Grip { MOVE, ROTATE }

    private val d = context.resources.displayMetrics.density
    var items: List<Item> = emptyList()
    var selectedKey: String? = null

    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.argb(210, 0, 0, 0)
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
        textSize = 11.5f * d
        letterSpacing = 0.08f
    }
    private val rotateIcon = ContextCompat.getDrawable(context, R.drawable.ic_rotate_handle)?.mutate()

    private var dragKey: String? = null
    private var dragGrip: Grip? = null
    private var dragGate: Gate? = null
    private var downX = 0f
    private var downY = 0f
    private var moved = false

    /* ------------------------------ Geometria ---------------------------- */

    private class Screen(val c: PointF, val a: PointF, val b: PointF, val handle: PointF, val dirX: Float, val dirY: Float)

    private fun screen(map: MapView, g: Gate): Screen {
        val proj = map.projection
        val (e1, e2) = Geo.gateEndpoints(g)
        fun px(lat: Double, lon: Double) = Point().also { proj.toPixels(GeoPoint(lat, lon), it) }
            .let { PointF(it.x.toFloat(), it.y.toFloat()) }
        val c = px(g.lat, g.lon)
        val a = px(e1.lat, e1.lon)
        val b = px(e2.lat, e2.lon)
        var dx = b.x - a.x
        var dy = b.y - a.y
        var len = hypot(dx, dy)
        if (len < 0.5f) {
            // Linea non visibile a questo zoom: direzione dall'orientamento.
            val r = Math.toRadians(g.headingDeg)
            dx = kotlin.math.sin(r).toFloat()
            dy = -kotlin.math.cos(r).toFloat()
            len = 1f
        }
        var ux = dx / len
        var uy = dy / len
        // Verso dell'asse: rivolto al baricentro delle linee (interno del
        // circuito). La maniglia sta da quel lato, l'etichetta dal lato esterno.
        val cen = centroid(map)
        if ((cen.x - c.x) * ux + (cen.y - c.y) * uy < 0) {
            ux = -ux
            uy = -uy
        }
        // A zoom bassi la linea vera è più corta del pallino: la si disegna
        // con una lunghezza minima perché l'orientamento resti leggibile.
        val minHalf = 26 * d
        val (va, vb) = if (len / 2 < minHalf) {
            PointF(c.x - ux * minHalf, c.y - uy * minHalf) to PointF(c.x + ux * minHalf, c.y + uy * minHalf)
        } else a to b
        val off = max(hypot(vb.x - c.x, vb.y - c.y) + 22 * d, 58 * d)
        return Screen(c, va, vb, PointF(c.x + ux * off, c.y + uy * off), ux, uy)
    }

    /** Baricentro (in pixel) dei centri di tutte le linee. */
    private fun centroid(map: MapView): PointF {
        if (items.isEmpty()) return PointF(0f, 0f)
        val p = Point()
        var sx = 0f
        var sy = 0f
        items.forEach {
            map.projection.toPixels(GeoPoint(it.gate.lat, it.gate.lon), p)
            sx += p.x
            sy += p.y
        }
        return PointF(sx / items.size, sy / items.size)
    }

    /* ------------------------------- Disegno ----------------------------- */

    override fun draw(canvas: Canvas, map: MapView, shadow: Boolean) {
        if (shadow) return
        // Le linee selezionate in primo piano.
        val ordered = items.sortedBy { it.key == selectedKey }
        for (it in ordered) drawItem(canvas, map, it, it.key == selectedKey)
    }

    private fun drawItem(canvas: Canvas, map: MapView, item: Item, selected: Boolean) {
        val g = if (item.key == dragKey) dragGate ?: item.gate else item.gate
        val s = screen(map, g)
        val w = (if (selected) 8f else 6f) * d

        // Asse tratteggiato verso la maniglia (solo se selezionata).
        if (selected) {
            ring.color = Color.argb(160, 255, 255, 255)
            ring.strokeWidth = 1.5f * d
            ring.pathEffect = DashPathEffect(floatArrayOf(4 * d, 4 * d), 0f)
            canvas.drawLine(s.c.x, s.c.y, s.handle.x, s.handle.y, ring)
            ring.pathEffect = null
        }

        // Linea: contorno scuro + colore (o scacchi bianco/nero).
        outline.strokeWidth = w + 4 * d
        canvas.drawLine(s.a.x, s.a.y, s.b.x, s.b.y, outline)
        if (item.checkered) {
            line.strokeWidth = w
            line.pathEffect = null
            line.color = Color.WHITE
            canvas.drawLine(s.a.x, s.a.y, s.b.x, s.b.y, line)
            line.color = Color.BLACK
            line.pathEffect = DashPathEffect(floatArrayOf(w, w), 0f)
            canvas.drawLine(s.a.x, s.a.y, s.b.x, s.b.y, line)
            line.pathEffect = null
        } else {
            line.strokeWidth = w
            line.color = item.color
            canvas.drawLine(s.a.x, s.a.y, s.b.x, s.b.y, line)
        }
        // Estremità.
        fill.color = item.color
        canvas.drawCircle(s.a.x, s.a.y, 3.5f * d, fill)
        canvas.drawCircle(s.b.x, s.b.y, 3.5f * d, fill)

        // Maniglia di rotazione.
        if (selected || item.key == dragKey) {
            fill.color = Color.WHITE
            canvas.drawCircle(s.handle.x, s.handle.y, 15 * d, fill)
            ring.color = item.color
            ring.strokeWidth = 3 * d
            canvas.drawCircle(s.handle.x, s.handle.y, 15 * d, ring)
            rotateIcon?.let {
                val r = (10 * d).toInt()
                it.setBounds((s.handle.x - r).toInt(), (s.handle.y - r).toInt(), (s.handle.x + r).toInt(), (s.handle.y + r).toInt())
                it.setTint(item.color)
                it.draw(canvas)
            }
        }

        // Pallino centrale (spostamento).
        fill.color = item.color
        canvas.drawCircle(s.c.x, s.c.y, (if (selected) 12f else 10f) * d, fill)
        ring.color = Color.WHITE
        ring.strokeWidth = 2.5f * d
        canvas.drawCircle(s.c.x, s.c.y, (if (selected) 12f else 10f) * d, ring)

        // Etichetta, dal lato opposto alla maniglia.
        val tw = text.measureText(item.label)
        // Oltre l'estremità della linea (non sopra la linea né sul percorso).
        val labelDist = hypot(s.a.x - s.c.x, s.a.y - s.c.y) + 12 * d + tw / 2 * kotlin.math.abs(s.dirX) + 11 * d * kotlin.math.abs(s.dirY)
        // Sempre dentro la mappa visibile (a destra ci sono i pulsanti laterali).
        val lx = (s.c.x - s.dirX * labelDist).coerceIn(tw / 2 + 14 * d, map.width - tw / 2 - 76 * d)
        val ly = (s.c.y - s.dirY * labelDist).coerceIn(150 * d, map.height - 24 * d)
        val rect = RectF(lx - tw / 2 - 8 * d, ly - 11 * d, lx + tw / 2 + 8 * d, ly + 11 * d)
        fill.color = item.color
        canvas.drawRoundRect(rect, 11 * d, 11 * d, fill)
        text.color = if (luminance(item.color) > 0.55) Color.BLACK else Color.WHITE
        canvas.drawText(item.label, lx, ly - (text.descent() + text.ascent()) / 2, text)
    }

    private fun luminance(c: Int) =
        (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255.0

    /* ------------------------------- Tocchi ------------------------------ */

    private fun hit(map: MapView, x: Float, y: Float): Pair<Item, Grip>? {
        val r = 26 * d
        // Prima la linea selezionata (maniglia compresa), poi le altre.
        val ordered = items.sortedByDescending { it.key == selectedKey }
        for (it in ordered) {
            val s = screen(map, it.gate)
            if (it.key == selectedKey && hypot(x - s.handle.x, y - s.handle.y) <= r) return it to Grip.ROTATE
            if (hypot(x - s.c.x, y - s.c.y) <= r) return it to Grip.MOVE
        }
        return null
    }

    /** Linea toccata lungo il suo tratto (per la selezione). */
    private fun hitLine(map: MapView, x: Float, y: Float): Item? = items.firstOrNull { item ->
        val s = screen(map, item.gate)
        distToSegment(x, y, s.a, s.b) <= 16 * d
    }

    private fun distToSegment(x: Float, y: Float, a: PointF, b: PointF): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val l2 = dx * dx + dy * dy
        if (l2 < 1e-3f) return hypot(x - a.x, y - a.y)
        val t = (((x - a.x) * dx + (y - a.y) * dy) / l2).coerceIn(0f, 1f)
        return hypot(x - (a.x + t * dx), y - (a.y + t * dy))
    }

    override fun onTouchEvent(event: MotionEvent, map: MapView): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (event.pointerCount > 1) return false
                val h = hit(map, event.x, event.y) ?: return false
                dragKey = h.first.key
                dragGrip = h.second
                dragGate = h.first.gate
                downX = event.x
                downY = event.y
                moved = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val key = dragKey ?: return false
                val g = dragGate ?: return false
                if (!moved && hypot(event.x - downX, event.y - downY) < 6 * d) return true
                moved = true
                val geo = map.projection.fromPixels(event.x.toInt(), event.y.toInt())
                val p = LatLon(geo.latitude, geo.longitude)
                dragGate = when (dragGrip) {
                    Grip.ROTATE -> g.copy(headingDeg = normalize(Geo.bearingDeg(g.center, p)))
                    else -> g.copy(lat = p.lat, lon = p.lon)
                }
                listener.onGateChanging(key, dragGate!!)
                map.invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val key = dragKey ?: return false
                val g = dragGate
                val wasMoved = moved
                dragKey = null
                dragGrip = null
                dragGate = null
                if (wasMoved && g != null) listener.onGateChanged(key, g) else listener.onGateTapped(key)
                map.invalidate()
                return true
            }
        }
        return false
    }

    override fun onSingleTapConfirmed(e: MotionEvent, map: MapView): Boolean {
        val item = hitLine(map, e.x, e.y) ?: return false
        listener.onGateTapped(item.key)
        return true
    }

    companion object {
        /** Orientamento della linea in [0, 180): una linea non ha verso. */
        fun normalize(deg: Double): Double = ((deg % 180.0) + 180.0) % 180.0
    }
}
