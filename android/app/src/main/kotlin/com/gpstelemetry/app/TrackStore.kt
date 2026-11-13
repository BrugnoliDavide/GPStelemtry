package com.gpstelemetry.app

import android.content.Context
import com.gpstelemetry.core.Json
import com.gpstelemetry.core.Sectors
import com.gpstelemetry.core.Session
import com.gpstelemetry.core.SessionStatus
import com.gpstelemetry.core.SessionSummary
import com.gpstelemetry.core.Track
import com.gpstelemetry.core.summary
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Persistenza locale su file JSON nella memoria privata dell'app:
 *  - tracks.json: elenco dei tracciati;
 *  - sessions/<id>.json: una sessione per file (possono essere grandi);
 *  - sessions/index/<id>.json: riepilogo leggero (risultati, senza
 *    telemetria) per elenchi, medie e record. Creato anche per le sessioni
 *    salvate da versioni precedenti, alla prima lettura.
 */
object TrackStore {

    fun newId(): String = UUID.randomUUID().toString()

    private fun tracksFile(ctx: Context) = File(ctx.filesDir, "tracks.json")
    private fun sessionsDir(ctx: Context) = File(ctx.filesDir, "sessions").apply { mkdirs() }
    private fun indexDir(ctx: Context) = File(sessionsDir(ctx), "index").apply { mkdirs() }

    /* ----------------------------- Tracciati ----------------------------- */

    @Synchronized
    fun listTracks(ctx: Context): List<Track> {
        val f = tracksFile(ctx)
        if (!f.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            List(arr.length()) { Json.parseTrack(arr.getJSONObject(it)) }
        }.getOrElse { emptyList() }
    }

    fun getTrack(ctx: Context, id: String): Track? = listTracks(ctx).find { it.id == id }

    @Synchronized
    fun saveTrack(ctx: Context, track: Track) {
        val all = listTracks(ctx).filter { it.id != track.id } + track
        writeTracks(ctx, all)
    }

    @Synchronized
    fun deleteTrack(ctx: Context, id: String) {
        writeTracks(ctx, listTracks(ctx).filter { it.id != id })
    }

    private fun writeTracks(ctx: Context, tracks: List<Track>) {
        val arr = JSONArray()
        tracks.sortedByDescending { it.createdAt }.forEach { arr.put(Json.track(it)) }
        val tmp = File(tracksFile(ctx).parentFile, "tracks.json.tmp")
        tmp.writeText(arr.toString())
        replace(tmp, tracksFile(ctx))
    }

    /* ----------------------------- Sessioni ------------------------------ */

    fun listSessions(ctx: Context): List<Session> =
        sessionsDir(ctx).listFiles { f -> f.isFile && f.extension == "json" }
            ?.mapNotNull { f ->
                runCatching { Json.parseSession(JSONObject(f.readText())) }.getOrNull()
            }
            ?.sortedByDescending { it.startedAt }
            ?: emptyList()

    fun getSession(ctx: Context, id: String): Session? {
        val f = File(sessionsDir(ctx), "$id.json")
        if (!f.exists()) return null
        return runCatching { Json.parseSession(JSONObject(f.readText())) }.getOrNull()
    }

    fun saveSession(ctx: Context, session: Session) {
        val tmp = File(sessionsDir(ctx), "${session.id}.json.tmp")
        tmp.writeText(Json.session(session).toString())
        replace(tmp, File(sessionsDir(ctx), "${session.id}.json"))
        writeSummary(ctx, session.summary())
    }

    fun deleteSession(ctx: Context, id: String) {
        File(sessionsDir(ctx), "$id.json").delete()
        File(indexDir(ctx), "$id.json").delete()
    }

    fun sessionJson(session: Session): String = Json.session(session).toString(1)

    private fun writeSummary(ctx: Context, s: SessionSummary) {
        val tmp = File(indexDir(ctx), "${s.id}.json.tmp")
        tmp.writeText(Json.summary(s).toString())
        replace(tmp, File(indexDir(ctx), "${s.id}.json"))
    }

    /** Riepiloghi di tutte le sessioni, dal più recente. */
    @Synchronized
    fun listSummaries(ctx: Context): List<SessionSummary> {
        val idx = indexDir(ctx)
        val out = mutableListOf<SessionSummary>()
        sessionsDir(ctx).listFiles { f -> f.isFile && f.extension == "json" }?.forEach { f ->
            val id = f.nameWithoutExtension
            val iFile = File(idx, "$id.json")
            val sum = runCatching { Json.parseSummary(JSONObject(iFile.readText())) }.getOrNull()
                ?: runCatching {
                    // Sessione senza indice (versione precedente): lo crea ora.
                    Json.parseSession(JSONObject(f.readText())).summary().also { writeSummary(ctx, it) }
                }.getOrNull()
            sum?.let { out.add(it) }
        }
        return out.sortedByDescending { it.startedAt }
    }

    /** Giri conclusi di un tracciato (esclusa eventualmente una sessione). */
    fun finishedResults(
        summaries: List<SessionSummary>,
        trackId: String,
        excludeId: String? = null,
    ) = summaries.filter {
        it.trackId == trackId && it.id != excludeId && it.status == SessionStatus.FINISHED
    }.mapNotNull { s -> s.result?.copy(status = SessionStatus.FINISHED) }

    /** Riferimenti storici (medie e record) di un tracciato. */
    fun benchmark(ctx: Context, track: Track, excludeId: String? = null): Sectors.Benchmark =
        benchmark(ctx, track.id, track.splits.size, excludeId)

    fun benchmark(ctx: Context, trackId: String, splitCount: Int, excludeId: String? = null): Sectors.Benchmark =
        Sectors.benchmark(finishedResults(listSummaries(ctx), trackId, excludeId), splitCount)
}

/** Sostituzione del file: rename atomico, con ripiego dove il rename non sovrascrive. */
private fun replace(tmp: File, target: File) {
    if (!tmp.renameTo(target)) {
        target.delete()
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }
}
