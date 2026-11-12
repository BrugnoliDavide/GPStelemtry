package com.gpstelemetry.app

import androidx.core.content.ContextCompat
import com.google.android.material.chip.Chip

/**
 * Stile "vetro" per le chip create dal codice (suggerimenti, veicoli,
 * criteri di ricerca): come Chip.Editor, arancione quando selezionate.
 */
fun Chip.glass(): Chip {
    val ctx = context
    chipBackgroundColor = ContextCompat.getColorStateList(ctx, R.color.toggle_bg)
    setTextColor(ContextCompat.getColorStateList(ctx, R.color.toggle_text))
    chipStrokeColor = ContextCompat.getColorStateList(ctx, R.color.chip_stroke)
    chipStrokeWidth = resources.displayMetrics.density
    isCheckedIconVisible = false
    textSize = 14f
    return this
}
