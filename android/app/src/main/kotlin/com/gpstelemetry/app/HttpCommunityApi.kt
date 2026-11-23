package com.gpstelemetry.app

import com.gpstelemetry.core.CommunityJson
import com.gpstelemetry.core.DriverProfile
import com.gpstelemetry.core.Friend
import com.gpstelemetry.core.Friends
import com.gpstelemetry.core.FriendsBoard
import com.gpstelemetry.core.Json
import com.gpstelemetry.core.LapSubmission
import com.gpstelemetry.core.LeaderboardEntry
import com.gpstelemetry.core.NicknameCheck
import com.gpstelemetry.core.ProfileVisibility
import com.gpstelemetry.core.PublishedTrack
import com.gpstelemetry.core.Track
import com.gpstelemetry.core.TrackQuery
import com.gpstelemetry.core.UserAccount
import com.gpstelemetry.core.VehicleType
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Client del SERVER VERO della community (cartella server/, contratto in
 * android/docs/API.md).
 *
 * Server occupato o rete assente: la richiesta ATTENDE e riprova da sola
 * ([maxAttempts] tentativi). Con 503/429 aspetta i secondi indicati dal
 * server (Retry-After), altrimenti 1, 2, 4… secondi, mai oltre [maxWaitMs].
 * Se non basta, l'errore è [CommunityException.retryable]: i giri finiscono
 * nella coda ([LapQueue]) e partono più tardi.
 */
class HttpCommunityApi(
    baseUrl: String,
    private val maxAttempts: Int = 3,
    private val maxWaitMs: Long = 10_000,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    client: OkHttpClient? = null,
) : CommunityApi {

    private val base: HttpUrl = baseUrl.trimEnd('/').toHttpUrl()

    override val isAvailable = true
    override val isMock = false
    override val serverId: String = base.toString()

    private val http = client ?: OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // La prima richiesta dopo un riavvio del tunnel può impiegare decine di secondi.
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val executor = Executors.newFixedThreadPool(2)

    private fun <T> async(callback: (Result<T>) -> Unit, block: () -> T) {
        executor.execute { callback(runCatching(block)) }
    }

    /* ------------------------------ Trasporto ---------------------------- */

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    /** Risposta riuscita (2xx), oppure 404 se [allow404]. */
    private class Response(val code: Int, val body: String) {
        fun obj() = JSONObject(body)
        fun arr() = JSONArray(body)
    }

    private fun url(vararg segments: String, query: Map<String, String> = emptyMap()): HttpUrl {
        val b = base.newBuilder()
        segments.forEach { b.addPathSegment(it) }
        query.forEach { (k, v) -> b.addQueryParameter(k, v) }
        return b.build()
    }

    private fun call(
        method: String,
        url: HttpUrl,
        token: String? = null,
        body: RequestBody? = null,
        allow404: Boolean = false,
    ): Response {
        var attempt = 0
        while (true) {
            attempt++
            val req = Request.Builder().url(url).method(method, body)
                .header("Accept", "application/json")
                .apply { if (token != null) header("Authorization", "Bearer $token") }
                .build()
            val (code, text, retryAfter) = try {
                http.newCall(req).execute().use { r ->
                    Triple(r.code, r.body?.string().orEmpty(), r.header("Retry-After")?.toLongOrNull())
                }
            } catch (e: IOException) {
                if (attempt >= maxAttempts) {
                    throw CommunityException("Server non raggiungibile: controlla la connessione", retryable = true)
                }
                sleep(backoff(attempt))
                continue
            }
            when {
                code in 200..299 -> return Response(code, text)
                code == 404 && allow404 -> return Response(code, text)
                code == 503 || code == 429 -> {
                    if (attempt >= maxAttempts) {
                        throw CommunityException(message(text) ?: "Server occupato, riprova tra poco", retryable = true)
                    }
                    sleep(min(maxWaitMs, retryAfter?.let { it * 1000 } ?: backoff(attempt)))
                }
                else -> throw CommunityException(
                    message(text) ?: "Errore del server ($code)",
                    retryable = code >= 500,
                    status = code,
                )
            }
        }
    }

    private fun backoff(attempt: Int): Long = min(maxWaitMs, 1000L shl (attempt - 1))

    private fun message(body: String): String? =
        runCatching { JSONObject(body).optString("message").takeIf { it.isNotBlank() } }.getOrNull()

    private fun JSONObject.body(): RequestBody = toString().toRequestBody(jsonType)

    /* ------------------------------ Utenti ------------------------------- */

    private fun account(o: JSONObject, token: String?) = UserAccount(
        userId = o.getString("userId"),
        nickname = o.getString("nickname"),
        token = Json.optStr(o, "token") ?: token ?: throw CommunityException("Risposta del server senza token"),
        visibility = runCatching { ProfileVisibility.valueOf(o.getString("visibility")) }.getOrDefault(ProfileVisibility.PUBLIC),
        avatarUrl = Json.optStr(o, "avatarUrl"),
    )

    override fun checkNickname(nickname: String, callback: (Result<NicknameCheck>) -> Unit) = async(callback) {
        val o = call("GET", url("nicknames", nickname.trim(), "availability")).obj()
        NicknameCheck(
            nickname = o.getString("nickname"),
            available = o.getBoolean("available"),
            reason = Json.optStr(o, "reason"),
            suggestions = o.optJSONArray("suggestions")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList(),
        )
    }

    override fun registerNickname(nickname: String, current: UserAccount?, callback: (Result<UserAccount>) -> Unit) =
        async(callback) {
            val body = JSONObject().put("nickname", nickname.trim()).body()
            if (current != null) {
                try {
                    return@async account(call("PATCH", url("users", "me"), current.token, body).obj(), current.token)
                } catch (e: CommunityException) {
                    // Account non più valido (es. eliminato): se ne crea uno nuovo.
                    if (e.status != 401) throw e
                }
            }
            account(call("POST", url("users"), body = body).obj(), null)
        }

    override fun updateProfile(account: UserAccount, visibility: ProfileVisibility, callback: (Result<UserAccount>) -> Unit) =
        async(callback) {
            val body = JSONObject().put("visibility", visibility.name).body()
            account(call("PATCH", url("users", "me"), account.token, body).obj(), account.token)
        }

    override fun uploadAvatar(account: UserAccount, jpeg: ByteArray, callback: (Result<String>) -> Unit) = async(callback) {
        call("PUT", url("users", "me", "avatar"), account.token, jpeg.toRequestBody("image/jpeg".toMediaType()))
            .obj().getString("avatarUrl")
    }

    /* ---------------------------- Tracciati ------------------------------ */

    override fun publishTrack(account: UserAccount, track: Track, callback: (Result<PublishedTrack>) -> Unit) =
        async(callback) {
            val body = JSONObject().put("track", Json.track(track)).body()
            CommunityJson.parsePublishedTrack(call("POST", url("tracks"), account.token, body).obj(), account.nickname)
        }

    override fun unpublishTrack(account: UserAccount, publishedId: String, callback: (Result<Unit>) -> Unit) =
        async(callback) {
            call("DELETE", url("tracks", publishedId), account.token, allow404 = true)
            Unit
        }

    override fun searchTracks(query: TrackQuery, me: UserAccount?, callback: (Result<List<PublishedTrack>>) -> Unit) =
        async(callback) {
            val u = when (query) {
                is TrackQuery.All -> url("tracks")
                is TrackQuery.ByName -> url("tracks", "search", query = mapOf("name" to query.text))
                is TrackQuery.ByDriver -> url("tracks", "search", query = mapOf("driver" to query.nickname))
                is TrackQuery.Nearby -> url(
                    "tracks", "nearby",
                    query = mapOf("lat" to query.lat.toString(), "lon" to query.lon.toString(), "radiusKm" to query.radiusKm.toString()),
                )
            }
            val a = call("GET", u, me?.token).arr()
            List(a.length()) { CommunityJson.parsePublishedTrack(a.getJSONObject(it), me?.nickname) }
        }

    override fun getTrack(publishedId: String, me: UserAccount?, callback: (Result<PublishedTrack>) -> Unit) =
        async(callback) {
            CommunityJson.parsePublishedTrack(call("GET", url("tracks", publishedId), me?.token).obj(), me?.nickname)
        }

    /* ---------------------------- Risultati ------------------------------ */

    override fun submitLap(account: UserAccount, lap: LapSubmission, callback: (Result<LeaderboardEntry>) -> Unit) =
        async(callback) { submitLapSync(account, lap) }

    /** Invio sincrono (per la coda in background). */
    fun submitLapSync(account: UserAccount, lap: LapSubmission): LeaderboardEntry {
        val body = CommunityJson.lap(lap).body()
        return CommunityJson.parseEntry(
            call("POST", url("tracks", lap.publishedTrackId, "laps"), account.token, body).obj(),
            account.nickname,
        )
    }

    override fun deleteLap(account: UserAccount, lapId: String, callback: (Result<Unit>) -> Unit) = async(callback) {
        call("DELETE", url("laps", lapId), account.token, allow404 = true)
        Unit
    }

    override fun leaderboard(
        publishedId: String,
        me: UserAccount?,
        vehicleType: VehicleType?,
        callback: (Result<List<LeaderboardEntry>>) -> Unit,
    ) = async(callback) {
            val q = vehicleType?.let { mapOf("vehicleType" to it.name) } ?: emptyMap()
            val a = call("GET", url("tracks", publishedId, "leaderboard", query = q), me?.token).arr()
            List(a.length()) { CommunityJson.parseEntry(a.getJSONObject(it), me?.nickname) }
        }

    /* ------------------------------ Amici -------------------------------- */

    override fun findDriver(nickname: String, me: UserAccount?, callback: (Result<DriverProfile?>) -> Unit) =
        async(callback) {
            val r = call("GET", url("drivers", nickname.trim()), me?.token, allow404 = true)
            if (r.code == 404) null else Friends.parseDriver(r.obj())
        }

    override fun searchDrivers(text: String, me: UserAccount?, limit: Int, callback: (Result<List<DriverProfile>>) -> Unit) =
        async(callback) {
            val q = mapOf("q" to text.trim(), "limit" to limit.toString())
            Friends.parseDrivers(call("GET", url("drivers", query = q), me?.token).arr())
        }

    override fun friendsTimes(
        publishedId: String,
        friends: List<Friend>,
        me: UserAccount?,
        callback: (Result<FriendsBoard>) -> Unit,
    ) = async(callback) {
        val body = Friends.requestBody(friends).body()
        Friends.parseBoard(call("POST", url("tracks", publishedId, "friends"), me?.token, body).obj(), friends, me?.nickname)
    }

    /* ----------------------- Dati e account ------------------------------ */

    override fun deleteAllLaps(account: UserAccount, callback: (Result<Int>) -> Unit) = async(callback) {
        call("DELETE", url("users", "me", "laps"), account.token).obj().getInt("deleted")
    }

    override fun deleteAccount(account: UserAccount, callback: (Result<Unit>) -> Unit) = async(callback) {
        call("DELETE", url("users", "me"), account.token)
        Unit
    }
}
