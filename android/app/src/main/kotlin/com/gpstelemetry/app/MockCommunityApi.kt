package com.gpstelemetry.app

import android.content.Context
import com.gpstelemetry.core.CommunityJson
import com.gpstelemetry.core.Geo
import com.gpstelemetry.core.Json
import com.gpstelemetry.core.LapSubmission
import com.gpstelemetry.core.LeaderboardEntry
import com.gpstelemetry.core.Nickname
import com.gpstelemetry.core.NicknameCheck
import com.gpstelemetry.core.ProfileVisibility
import com.gpstelemetry.core.PublishedTrack
import com.gpstelemetry.core.Track
import com.gpstelemetry.core.TrackQuery
import com.gpstelemetry.core.UserAccount
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/**
 * SERVER SIMULATO (mock) della community: implementa [CommunityApi] con un
 * piccolo "database" JSON nella memoria privata dell'app
 * (files/mock_server/db.json), con un ritardo che imita la rete.
 *
 * Riproduce le regole che avrà il server vero:
 *  - nickname unici (maiuscole indifferenti), formato [Nickname.validate],
 *    alcuni riservati e alcuni già "presi" da utenti di esempio;
 *  - pubblicazione/ritiro dei tracciati e dei giri, solo per il proprietario;
 *  - classifica = miglior giro di ogni pilota; i profili privati compaiono
 *    come "Pilota privato";
 *  - ricerca per vicinanza (dalla linea di partenza), nome o pilota.
 *
 * I dati restano sul telefono: serve per sviluppare e provare l'app finché
 * il server non è pronto.
 */
class MockCommunityApi(
    context: Context,
    /** Ritardo simulato di ogni chiamata (ms). */
    private val latencyMs: Long = 450,
) : CommunityApi {

    override val isAvailable = true
    override val isMock = true

    private val dir = File(context.filesDir, "mock_server").apply { mkdirs() }
    private val dbFile = File(dir, "db.json")
    private val avatarDir = File(dir, "avatars").apply { mkdirs() }
    private val executor = Executors.newSingleThreadExecutor()
    private val lock = Any()

    companion object {
        /** Nickname riservati dal sistema. */
        val RESERVED = setOf("admin", "root", "moderatore", "gpstelemetry", "support", "staff", "null")

        /** Utenti di esempio già registrati (per provare il "non disponibile"). */
        val SAMPLE_USERS = listOf("marta", "luca", "giorgio", "speedy", "pilota")

        private const val PRIVATE_NAME = "Pilota privato"

        /** Risposta delle funzioni predisposte ma non ancora attive. */
        const val FEATURE_SOON = "Funzione in arrivo: sarà attiva con il server della community."
    }

    /* ------------------------------ Database ----------------------------- */

    private fun load(): JSONObject {
        val db = runCatching { JSONObject(dbFile.readText()) }.getOrNull() ?: JSONObject()
        if (!db.has("users")) {
            db.put("users", JSONArray().apply {
                SAMPLE_USERS.forEach {
                    put(user(UUID.randomUUID().toString(), it.replaceFirstChar { c -> c.uppercase() }, ProfileVisibility.PUBLIC))
                }
            })
        }
        if (!db.has("tracks")) db.put("tracks", JSONArray())
        if (!db.has("laps")) db.put("laps", JSONArray())
        return db
    }

    private fun save(db: JSONObject) {
        val tmp = File(dir, "db.json.tmp")
        tmp.writeText(db.toString())
        if (!tmp.renameTo(dbFile)) {
            dbFile.delete()
            tmp.renameTo(dbFile)
        }
    }

    private fun user(id: String, nick: String, vis: ProfileVisibility, token: String = UUID.randomUUID().toString()) =
        JSONObject().put("id", id).put("nickname", nick).put("token", token)
            .put("visibility", vis.name).put("avatarUrl", JSONObject.NULL)

    private fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }

    private fun users(db: JSONObject) = db.getJSONArray("users").objects()
    private fun tracks(db: JSONObject) = db.getJSONArray("tracks").objects()
    private fun laps(db: JSONObject) = db.getJSONArray("laps").objects()

    private fun findUserByNick(db: JSONObject, nick: String) =
        users(db).find { Nickname.key(it.getString("nickname")) == Nickname.key(nick) }

    private fun account(u: JSONObject) = UserAccount(
        userId = u.getString("id"),
        nickname = u.getString("nickname"),
        token = u.getString("token"),
        visibility = ProfileVisibility.valueOf(u.getString("visibility")),
        avatarUrl = Json.optStr(u, "avatarUrl"),
    )

    /** Verifica il token: restituisce l'utente o fallisce. */
    private fun auth(db: JSONObject, a: UserAccount): JSONObject =
        users(db).find { it.getString("id") == a.userId && it.getString("token") == a.token }
            ?: throw CommunityException("Sessione non valida: registra di nuovo il nickname.")

    private fun replaceIn(db: JSONObject, key: String, list: List<JSONObject>) {
        db.put(key, JSONArray().apply { list.forEach { put(it) } })
    }

    /** Esegue [block] in background con il ritardo di rete simulato. */
    private fun <T> call(callback: (Result<T>) -> Unit, block: () -> T) {
        executor.execute {
            if (latencyMs > 0) Thread.sleep(latencyMs)
            val r = runCatching { synchronized(lock) { block() } }
            callback(r)
        }
    }

    /* ------------------------------ Utenti ------------------------------- */

    private fun checkSync(db: JSONObject, nick: String, selfId: String? = null): NicknameCheck {
        val n = nick.trim()
        Nickname.validate(n)?.let { return NicknameCheck(n, false, it) }
        if (Nickname.key(n) in RESERVED) {
            return NicknameCheck(n, false, "Nickname riservato", suggestions(db, n))
        }
        val owner = findUserByNick(db, n)
        if (owner != null && owner.getString("id") != selfId) {
            return NicknameCheck(n, false, "Già in uso", suggestions(db, n))
        }
        return NicknameCheck(n, true)
    }

    private fun suggestions(db: JSONObject, base: String): List<String> {
        val b = base.trim().take(Nickname.MAX - 3).ifBlank { "pilota" }
        val candidates = listOf("${b}_rc", "${b}${(10..99).random()}", "${b}.gp", "${b}_${(100..999).random()}")
        return candidates.filter {
            Nickname.validate(it) == null && Nickname.key(it) !in RESERVED && findUserByNick(db, it) == null
        }.take(3)
    }

    override fun checkNickname(nickname: String, callback: (Result<NicknameCheck>) -> Unit) =
        call(callback) { checkSync(load(), nickname) }

    override fun registerNickname(
        nickname: String,
        current: UserAccount?,
        callback: (Result<UserAccount>) -> Unit,
    ) = call(callback) {
        val db = load()
        val self = current?.let { runCatching { auth(db, it) }.getOrNull() }
        val check = checkSync(db, nickname, self?.getString("id"))
        if (!check.available) throw CommunityException(check.reason ?: "Nickname non disponibile")
        val u = if (self != null) {
            self.put("nickname", nickname.trim())
            replaceIn(db, "users", users(db).map { if (it.getString("id") == self.getString("id")) self else it })
            self
        } else {
            user(UUID.randomUUID().toString(), nickname.trim(), ProfileVisibility.PUBLIC).also {
                db.getJSONArray("users").put(it)
            }
        }
        save(db)
        account(u)
    }

    override fun updateProfile(
        account: UserAccount,
        visibility: ProfileVisibility,
        callback: (Result<UserAccount>) -> Unit,
    ) = call(callback) {
        val db = load()
        val u = auth(db, account).put("visibility", visibility.name)
        replaceIn(db, "users", users(db).map { if (it.getString("id") == u.getString("id")) u else it })
        save(db)
        account(u)
    }

    override fun uploadAvatar(account: UserAccount, jpeg: ByteArray, callback: (Result<String>) -> Unit) =
        call(callback) {
            val db = load()
            val u = auth(db, account)
            val f = File(avatarDir, "${u.getString("id")}.jpg")
            f.writeBytes(jpeg)
            val url = "mock://avatars/${f.name}"
            u.put("avatarUrl", url)
            replaceIn(db, "users", users(db).map { if (it.getString("id") == u.getString("id")) u else it })
            save(db)
            url
        }

    /* ---------------------------- Tracciati ------------------------------ */

    override fun publishTrack(account: UserAccount, track: Track, callback: (Result<PublishedTrack>) -> Unit) =
        call(callback) {
            val db = load()
            val u = auth(db, account)
            // Ripubblicazione: aggiorna la geometria mantenendo l'id e la classifica.
            val existing = track.remoteId?.let { id -> tracks(db).find { it.getString("id") == id } }
            val id = existing?.takeIf { it.getString("ownerId") == u.getString("id") }?.getString("id")
                ?: UUID.randomUUID().toString()
            val stored = track.copy(published = true, remoteId = id, remoteAuthor = null)
            val row = JSONObject()
                .put("id", id)
                .put("ownerId", u.getString("id"))
                .put("publishedAt", System.currentTimeMillis())
                .put("track", Json.track(stored))
            replaceIn(db, "tracks", tracks(db).filter { it.getString("id") != id } + row)
            save(db)
            published(db, row, account(u), null)
        }

    override fun unpublishTrack(account: UserAccount, publishedId: String, callback: (Result<Unit>) -> Unit) =
        call(callback) {
            val db = load()
            val u = auth(db, account)
            val row = tracks(db).find { it.getString("id") == publishedId }
                ?: throw CommunityException("Tracciato non trovato")
            if (row.getString("ownerId") != u.getString("id")) throw CommunityException("Non sei l'autore")
            replaceIn(db, "tracks", tracks(db).filter { it.getString("id") != publishedId })
            replaceIn(db, "laps", laps(db).filter { it.getString("trackId") != publishedId })
            save(db)
        }

    override fun searchTracks(
        query: TrackQuery,
        me: UserAccount?,
        callback: (Result<List<PublishedTrack>>) -> Unit,
    ) = call(callback) {
        val db = load()
        val usersById = users(db).associateBy { it.getString("id") }
        val all = tracks(db)
        val rows: List<Pair<JSONObject, Double?>> = when (query) {
            is TrackQuery.All -> all.sortedByDescending { it.getLong("publishedAt") }.map { it to null }
            is TrackQuery.ByName -> all.filter {
                it.getJSONObject("track").getString("name").contains(query.text.trim(), ignoreCase = true)
            }.map { it to null }
            is TrackQuery.ByDriver -> {
                val q = query.nickname.trim()
                val matching = usersById.values.filter {
                    it.getString("nickname").contains(q, ignoreCase = true) &&
                        (it.getString("visibility") == ProfileVisibility.PUBLIC.name || it.getString("id") == me?.userId)
                }.map { it.getString("id") }.toSet()
                val lapTracks = laps(db).filter { it.getString("userId") in matching }.map { it.getString("trackId") }.toSet()
                all.filter { it.getString("ownerId") in matching || it.getString("id") in lapTracks }.map { it to null }
            }
            is TrackQuery.Nearby -> all.map { row ->
                val s = Json.parseTrack(row.getJSONObject("track")).start
                row to Geo.haversine(query.lat, query.lon, s.lat, s.lon)
            }.filter { it.second!! <= query.radiusKm * 1000 }.sortedBy { it.second }
        }
        rows.map { (row, dist) -> published(db, row, me, dist, usersById) }
    }

    override fun getTrack(publishedId: String, me: UserAccount?, callback: (Result<PublishedTrack>) -> Unit) =
        call(callback) {
            val db = load()
            val row = tracks(db).find { it.getString("id") == publishedId }
                ?: throw CommunityException("Tracciato non più disponibile")
            published(db, row, me, null)
        }

    /** Costruisce il tracciato pubblicato con autore e podio. */
    private fun published(
        db: JSONObject,
        row: JSONObject,
        me: UserAccount?,
        distance: Double?,
        usersById: Map<String, JSONObject> = users(db).associateBy { it.getString("id") },
    ): PublishedTrack {
        val id = row.getString("id")
        val board = board(db, id, me, usersById)
        return PublishedTrack(
            id = id,
            track = Json.parseTrack(row.getJSONObject("track")),
            author = displayName(usersById[row.getString("ownerId")], me),
            publishedAt = row.getLong("publishedAt"),
            drivers = board.size,
            topTimes = board.take(3),
            distanceM = distance,
        )
    }

    private fun displayName(u: JSONObject?, me: UserAccount?): String = when {
        u == null -> "Utente rimosso"
        u.getString("id") == me?.userId -> u.getString("nickname")
        u.getString("visibility") == ProfileVisibility.PRIVATE.name -> PRIVATE_NAME
        else -> u.getString("nickname")
    }

    /* ---------------------------- Risultati ------------------------------ */

    override fun submitLap(account: UserAccount, lap: LapSubmission, callback: (Result<LeaderboardEntry>) -> Unit) =
        call(callback) {
            val db = load()
            val u = auth(db, account)
            if (tracks(db).none { it.getString("id") == lap.publishedTrackId }) {
                throw CommunityException("Il tracciato non è più pubblicato")
            }
            if (lap.lapMs <= 0) throw CommunityException("Giro non valido")
            val lapId = UUID.randomUUID().toString()
            db.getJSONArray("laps").put(
                CommunityJson.lap(lap)
                    .put("id", lapId)
                    .put("trackId", lap.publishedTrackId)
                    .put("userId", u.getString("id"))
            )
            save(db)
            val board = board(db, lap.publishedTrackId, account(u))
            board.find { it.lapId == lapId }
                ?: LeaderboardEntry(0, u.getString("nickname"), lap.lapMs, lap.sectorsMs, lap.setAt, true, lap.vehicle, lapId, lap.vehicleType)
        }

    override fun deleteLap(account: UserAccount, lapId: String, callback: (Result<Unit>) -> Unit) =
        call(callback) {
            val db = load()
            val u = auth(db, account)
            val lap = laps(db).find { it.getString("id") == lapId } ?: return@call
            if (lap.getString("userId") != u.getString("id")) throw CommunityException("Non è un tuo giro")
            replaceIn(db, "laps", laps(db).filter { it.getString("id") != lapId })
            save(db)
        }

    override fun leaderboard(publishedId: String, me: UserAccount?, callback: (Result<List<LeaderboardEntry>>) -> Unit) =
        call(callback) { board(load(), publishedId, me) }

    /* ----------------------- Dati e account (predisposti) ---------------- */

    // Interfaccia e contratto pronti (docs/API.md); la logica arriverà con
    // il server vero. Il mock verifica solo l'autenticazione.

    override fun deleteAllLaps(account: UserAccount, callback: (Result<Int>) -> Unit) =
        call(callback) {
            auth(load(), account)
            throw CommunityException(FEATURE_SOON)
        }

    override fun deleteAccount(account: UserAccount, callback: (Result<Unit>) -> Unit) =
        call(callback) {
            auth(load(), account)
            throw CommunityException(FEATURE_SOON)
        }

    /** Miglior giro di ogni pilota, dal più veloce. */
    private fun board(
        db: JSONObject,
        trackId: String,
        me: UserAccount?,
        usersById: Map<String, JSONObject> = users(db).associateBy { it.getString("id") },
    ): List<LeaderboardEntry> {
        val best = laps(db).filter { it.getString("trackId") == trackId }
            .groupBy { it.getString("userId") }
            .mapNotNull { (_, l) -> l.minByOrNull { it.getLong("lapMs") } }
            .sortedBy { it.getLong("lapMs") }
        return best.mapIndexed { i, o ->
            val lap = CommunityJson.parseLap(o)
            val uid = o.getString("userId")
            LeaderboardEntry(
                rank = i + 1,
                driver = displayName(usersById[uid], me),
                lapMs = lap.lapMs,
                sectorsMs = lap.sectorsMs,
                setAt = lap.setAt,
                isMe = uid == me?.userId,
                vehicle = lap.vehicle,
                vehicleType = lap.vehicleType,
                lapId = o.getString("id"),
            )
        }
    }
}
