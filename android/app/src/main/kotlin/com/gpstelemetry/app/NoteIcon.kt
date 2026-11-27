package com.gpstelemetry.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import androidx.core.content.ContextCompat

/** Segnaposto azzurro numerato delle note vocali sulla mappa (punta in basso = punto della nota). */
object NoteIcon {
    fun drawable(ctx: Context, number: Int): BitmapDrawable {
        val d = ctx.resources.displayMetrics.density
        val w = (30 * d).toInt()
        val h = (40 * d).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = w / 2f - 2 * d
        val cx = w / 2f
        val cy = r + 2 * d
        val pin = Path().apply {
            addCircle(cx, cy, r, Path.Direction.CW)
            moveTo(cx - r * 0.62f, cy + r * 0.78f)
            lineTo(cx, h - 1.5f * d)
            lineTo(cx + r * 0.62f, cy + r * 0.78f)
            close()
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(ctx, R.color.note) }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2 * d
            color = Color.WHITE
        }
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55000000 }
        c.save()
        c.translate(0f, 1.2f * d)
        c.drawPath(pin, shadow)
        c.restore()
        c.drawPath(pin, fill)
        c.drawPath(pin, stroke)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
            textSize = (if (number < 10) 14 else 12) * d
        }
        c.drawText(number.toString(), cx, cy - (text.descent() + text.ascent()) / 2, text)
        return BitmapDrawable(ctx.resources, bmp)
    }
}
