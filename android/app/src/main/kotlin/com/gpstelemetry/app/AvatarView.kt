package com.gpstelemetry.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

/**
 * Foto profilo circolare con anello arancione; senza foto mostra l'iniziale
 * del nickname (o un segno generico).
 */
class AvatarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = ContextCompat.getColor(context, R.color.accent)
    }
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.glass_top)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_primary)
        typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private val photo = Paint(Paint.ANTI_ALIAS_FLAG)

    private var bitmap: Bitmap? = null
    private var initial: String = "?"

    /** Spessore dell'anello in dp (0 = nessun anello). */
    var ringDp = 2f
        set(value) { field = value; invalidate() }

    fun set(bitmap: Bitmap?, nickname: String?) {
        this.bitmap = bitmap
        initial = nickname?.trim()?.firstOrNull()?.uppercase() ?: "?"
        photo.shader = bitmap?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val r = minOf(width, height) / 2f
        val cx = width / 2f
        val cy = height / 2f
        val ringW = ringDp * density
        val inner = r - ringW - if (ringW > 0) 1.5f * density else 0f
        val b = bitmap
        if (b != null) {
            val scale = (2 * inner) / minOf(b.width, b.height)
            val m = Matrix().apply {
                setScale(scale, scale)
                postTranslate(cx - b.width * scale / 2, cy - b.height * scale / 2)
            }
            photo.shader?.setLocalMatrix(m)
            canvas.drawCircle(cx, cy, inner, photo)
        } else {
            canvas.drawCircle(cx, cy, inner, bg)
            text.textSize = inner * 1.05f
            canvas.drawText(initial, cx, cy - (text.descent() + text.ascent()) / 2, text)
        }
        if (ringW > 0) {
            ring.strokeWidth = ringW
            canvas.drawCircle(cx, cy, r - ringW / 2, ring)
        }
    }
}
