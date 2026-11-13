package com.gpstelemetry.app

import android.content.Context

/**
 * Impostazioni dell'app (oltre a tema e profilo community).
 *
 * Attiva oggi: [units] (unità di misura).
 * PREDISPOSTE (salvate ma senza effetto finché il server della community
 * non è collegato): notifiche record battuto, notifiche giri degli amici,
 * modalità della classifica. Le azioni "elimina tempi" ed "elimina
 * profilo" hanno l'interfaccia e il contratto API pronti
 * (CommunityApi.deleteAllLaps / deleteAccount, vedi docs/API.md).
 */
object Settings {
    private const val PREFS = "settings"

    /** Classifiche: solo il miglior giro di ogni pilota, o tutti i giri. */
    enum class LeaderboardMode { BEST_ONLY, ALL_LAPS }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /* --------------------------- Unità di misura ------------------------- */

    fun units(ctx: Context): UnitSystem = runCatching {
        UnitSystem.valueOf(prefs(ctx).getString("units", null) ?: UnitSystem.METRIC.name)
    }.getOrDefault(UnitSystem.METRIC)

    fun setUnits(ctx: Context, u: UnitSystem) {
        prefs(ctx).edit().putString("units", u.name).apply()
        Units.system = u
    }

    /** Carica il sistema scelto (all'avvio dell'app). */
    fun applyUnits(ctx: Context) {
        Units.system = units(ctx)
    }

    /* ---------------------------- Notifiche ------------------------------ */

    /** Avviso quando un altro pilota batte un tuo record (predisposto). */
    fun notifyRecordBeaten(ctx: Context) = prefs(ctx).getBoolean("notifyRecordBeaten", true)
    fun setNotifyRecordBeaten(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("notifyRecordBeaten", v).apply()

    /** Avviso sui nuovi giri degli amici (predisposto). */
    fun notifyFriendsLaps(ctx: Context) = prefs(ctx).getBoolean("notifyFriendsLaps", false)
    fun setNotifyFriendsLaps(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("notifyFriendsLaps", v).apply()

    /* ----------------------- Tutorial e avvertenze ----------------------- */

    /** Vero dopo che l'utente ha completato il tutorial (e accettato le avvertenze). */
    fun tutorialDone(ctx: Context) = prefs(ctx).getBoolean("tutorialDone", false)

    /** Registra il completamento del tutorial e l'accettazione delle avvertenze. */
    fun setTutorialDone(ctx: Context, done: Boolean) {
        val e = prefs(ctx).edit().putBoolean("tutorialDone", done)
        if (done) e.putLong("disclaimerAcceptedAt", System.currentTimeMillis())
        e.apply()
    }

    /** Quando sono state accettate le avvertenze d'uso (0 = mai). */
    fun disclaimerAcceptedAt(ctx: Context) = prefs(ctx).getLong("disclaimerAcceptedAt", 0L)

    /* ---------------------------- Classifiche ---------------------------- */

    /** Modalità delle classifiche (predisposta: oggi si mostra il miglior giro). */
    fun leaderboardMode(ctx: Context): LeaderboardMode = runCatching {
        LeaderboardMode.valueOf(prefs(ctx).getString("leaderboardMode", null) ?: LeaderboardMode.BEST_ONLY.name)
    }.getOrDefault(LeaderboardMode.BEST_ONLY)

    fun setLeaderboardMode(ctx: Context, m: LeaderboardMode) =
        prefs(ctx).edit().putString("leaderboardMode", m.name).apply()
}
