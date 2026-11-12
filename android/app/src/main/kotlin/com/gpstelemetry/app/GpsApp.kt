package com.gpstelemetry.app

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/** Applicazione: applica il tema scelto (scuro predefinito) all'avvio. */
class GpsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ThemePrefs.apply(this)
    }
}

/**
 * Preferenza del tema: SCURO (nero vero, predefinito), CHIARO o di SISTEMA.
 * I colori dei due temi sono in res/values (chiaro) e res/values-night (scuro).
 */
object ThemePrefs {
    enum class Mode(val nightMode: Int) {
        DARK(AppCompatDelegate.MODE_NIGHT_YES),
        LIGHT(AppCompatDelegate.MODE_NIGHT_NO),
        SYSTEM(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
    }

    private const val PREFS = "ui"
    private const val KEY = "theme"

    fun get(ctx: Context): Mode {
        val name = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        return Mode.entries.find { it.name == name } ?: Mode.DARK
    }

    fun set(ctx: Context, mode: Mode) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, mode.name).apply()
        AppCompatDelegate.setDefaultNightMode(mode.nightMode)
    }

    fun apply(ctx: Context) = AppCompatDelegate.setDefaultNightMode(get(ctx).nightMode)
}
