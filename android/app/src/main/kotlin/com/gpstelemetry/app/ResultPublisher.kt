package com.gpstelemetry.app

import android.content.Context
import com.gpstelemetry.core.CommunityJson
import com.gpstelemetry.core.LeaderboardEntry
import com.gpstelemetry.core.Session
import com.gpstelemetry.core.SessionStatus
import kotlin.concurrent.thread

/**
 * Pubblicazione dei risultati (giri) nelle classifiche dei tracciati
 * collegati alla community (pubblicati dall'utente o importati da Esplora).
 */
object ResultPublisher {

    /** Perché un giro non può essere pubblicato (null = pubblicabile). */
    fun blocker(ctx: Context, s: Session): Int? {
        if (s.status != SessionStatus.FINISHED || s.result?.elapsedMs == null) return R.string.publish_block_unfinished
        val t = TrackStore.getTrack(ctx, s.trackId) ?: return R.string.publish_block_no_track
        if (t.remoteId == null) return R.string.publish_block_track_private
        if (Profile.account(ctx) == null) return R.string.publish_block_no_nickname
        return null
    }

    /** Invia il giro; aggiorna la sessione con l'id remoto. */
    fun submit(ctx: Context, s: Session, callback: (Result<LeaderboardEntry>) -> Unit) {
        val app = ctx.applicationContext
        val acc = Profile.account(app) ?: return callback(Result.failure(CommunityException("Nickname mancante")))
        val remote = TrackStore.getTrack(app, s.trackId)?.remoteId
            ?: return callback(Result.failure(CommunityException("Tracciato non pubblicato")))
        val lap = CommunityJson.lapFromSession(remote, s)
            ?: return callback(Result.failure(CommunityException("Giro non concluso")))
        Community.api.submitLap(acc, lap) { r ->
            r.onSuccess { e ->
                s.remoteLapId = e.lapId
                TrackStore.saveSession(app, s)
            }
            callback(r)
        }
    }

    /** Ritira il giro dalla classifica. */
    fun withdraw(ctx: Context, s: Session, callback: (Result<Unit>) -> Unit) {
        val app = ctx.applicationContext
        val acc = Profile.account(app) ?: return callback(Result.failure(CommunityException("Nickname mancante")))
        val lapId = s.remoteLapId ?: return callback(Result.success(Unit))
        Community.api.deleteLap(acc, lapId) { r ->
            r.onSuccess {
                s.remoteLapId = null
                TrackStore.saveSession(app, s)
            }
            callback(r)
        }
    }

    /**
     * Dopo un giro concluso: invio automatico se attivo nelle impostazioni
     * e se il tracciato è collegato alla community. Silenzioso in caso di
     * errore (si può ripubblicare a mano dal dettaglio sessione).
     */
    fun autoSubmit(ctx: Context, sessionId: String) {
        val app = ctx.applicationContext
        if (!Profile.autoPublishResults(app) || !Community.isReady) return
        thread(name = "auto-publish") {
            val s = TrackStore.getSession(app, sessionId) ?: return@thread
            if (s.remoteLapId != null || blocker(app, s) != null) return@thread
            submit(app, s) { }
        }
    }
}
