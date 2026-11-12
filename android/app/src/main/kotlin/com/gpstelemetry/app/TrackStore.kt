package com.gpstelemetry.app

import android.content.Context
import com.gpstelemetry.core.Json
import com.gpstelemetry.core.Session
import com.gpstelemetry.core.Track
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Persistenza locale su file JSON nella memoria privata dell'app:
 *  - tracks.json: elenco dei tracciati;
 *  - sessions/<id>.json: una sessione per file (possono essere grandi).
 */
object TrackStore {

    fun newId(): String = UUID.randomUUID().toString()

    private fun tracksFile(ctx: Context) = File(ctx.filesDir, "tracks.json")
    private fun sessionsDir(ctx: Context) = File(ctx.filesDir, "sessions").apply { mkdirs() }

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
        tmp.renameTo(tracksFile(ctx))
    }

    /* ----------------------------- Sessioni ------------------------------ */

    fun listSessions(ctx: Context): List<Session> =
        sessionsDir(ctx).listFiles { f -> f.extension == "json" }
            ?.mapNotNull { f ->
                runCatching { Json.parseSession(JSONObject(f.readText())) }.getOrNull()
            }
            ?.sortedByDescending { it.startedAt }
            ?: emptyList()

    fun saveSession(ctx: Context, session: Session) {
        val tmp = File(sessionsDir(ctx), "${session.id}.json.tmp")
        tmp.writeText(Json.session(session).toString())
        tmp.renameTo(File(sessionsDir(ctx), "${session.id}.json"))
    }

    fun deleteSession(ctx: Context, id: String) {
        File(sessionsDir(ctx), "$id.json").delete()
    }

    fun sessionJson(session: Session): String = Json.session(session).toString(1)
}
