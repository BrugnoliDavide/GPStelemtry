package com.gpstelemetry.app

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * CODA dei giri da inviare alla community. Un giro entra in coda quando
 * l'invio automatico non riesce (rete assente, server occupato o spento) e
 * resta salvato finché il server non lo accetta: WorkManager lo rimanda
 * appena c'è rete, anche ad app chiusa o dopo un riavvio del telefono,
 * aspettando sempre di più tra un tentativo e l'altro.
 */
object LapQueue {
    private const val PREFS = "lap_queue"
    private const val KEY = "sessions"
    private const val WORK = "lap-upload"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun pending(ctx: Context): Set<String> = prefs(ctx).getStringSet(KEY, emptySet())!!.toSet()

    @Synchronized
    fun add(ctx: Context, sessionId: String) {
        prefs(ctx).edit().putStringSet(KEY, pending(ctx) + sessionId).apply()
    }

    @Synchronized
    fun remove(ctx: Context, sessionId: String) {
        prefs(ctx).edit().putStringSet(KEY, pending(ctx) - sessionId).apply()
    }

    fun isPending(ctx: Context, sessionId: String) = sessionId in pending(ctx)

    fun clear(ctx: Context) {
        prefs(ctx).edit().remove(KEY).apply()
    }

    /** Mette in coda il giro e programma l'invio (subito se c'è rete). */
    fun enqueue(ctx: Context, sessionId: String) {
        add(ctx.applicationContext, sessionId)
        schedule(ctx)
    }

    /** Programma l'invio dei giri in coda, se ce ne sono. */
    fun schedule(ctx: Context) {
        val app = ctx.applicationContext
        if (pending(app).isEmpty()) return
        runCatching {
            val req = OneTimeWorkRequestBuilder<LapUploadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(app).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, req)
        }.onFailure { Log.w("LapQueue", "WorkManager non disponibile", it) }
    }

    /**
     * Invia tutti i giri in coda. Restituisce true se qualcuno va
     * ritentato più tardi (server occupato o non raggiungibile).
     */
    fun drain(ctx: Context): Boolean {
        val app = ctx.applicationContext
        if (!Community.isReady) Community.connect(app)
        var retry = false
        for (id in pending(app)) {
            val s = TrackStore.getSession(app, id)
            if (s == null || s.remoteLapId != null || ResultPublisher.blocker(app, s) != null) {
                remove(app, id) // eliminato, già inviato o non più inviabile
                continue
            }
            val r = ResultPublisher.submitBlocking(app, s)
            val e = r.exceptionOrNull()
            if (e == null || !(e is CommunityException && e.retryable)) {
                if (e != null) Log.w("LapQueue", "Giro $id scartato: ${e.message}")
                remove(app, id)
            } else {
                retry = true
            }
        }
        return retry
    }
}

/** Lavoro in background di [LapQueue]. */
class LapUploadWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result =
        if (LapQueue.drain(applicationContext)) Result.retry() else Result.success()
}
