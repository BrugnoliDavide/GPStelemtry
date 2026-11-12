package com.gpstelemetry.core

import org.json.JSONArray
import org.json.JSONObject

/*
 * Modelli della COMMUNITY: utenti (nickname), tracciati pubblicati,
 * classifiche. Sono il contratto tra l'app e il server (vedi
 * android/docs/API.md): l'app oggi usa un server simulato (mock) che
 * restituisce questi stessi oggetti.
 */

/** Visibilità del profilo verso gli altri utenti. */
enum class ProfileVisibility { PUBLIC, PRIVATE }

/** Account sul server (dopo la registrazione del nickname). */
data class UserAccount(
    val userId: String,
    val nickname: String,
    /** Token di sessione da inviare nelle chiamate autenticate. */
    val token: String,
    val visibility: ProfileVisibility = ProfileVisibility.PUBLIC,
    val avatarUrl: String? = null,
)

/** Esito della verifica di disponibilità di un nickname. */
data class NicknameCheck(
    val nickname: String,
    val available: Boolean,
    /** Motivo del rifiuto (formato non valido, già in uso, riservato…). */
    val reason: String? = null,
    /** Alternative libere proposte dal server. */
    val suggestions: List<String> = emptyList(),
)

/** Regole del nickname (identiche lato app e lato server). */
object Nickname {
    const val MIN = 3
    const val MAX = 20
    private val pattern = Regex("^[A-Za-z0-9_.]+$")

    /** Normalizzazione per il confronto di unicità (maiuscole indifferenti). */
    fun key(n: String) = n.trim().lowercase()

    /** null se valido, altrimenti il motivo (in italiano, per la UI). */
    fun validate(raw: String): String? {
        val n = raw.trim()
        return when {
            n.length < MIN -> "Almeno $MIN caratteri"
            n.length > MAX -> "Al massimo $MAX caratteri"
            !pattern.matches(n) -> "Solo lettere, numeri, _ e ."
            n.startsWith(".") || n.endsWith(".") -> "Non può iniziare o finire con un punto"
            else -> null
        }
    }
}

/** Tracciato pubblicato da un utente, con le statistiche pubbliche. */
data class PublishedTrack(
    /** Identificativo remoto (diverso dall'id locale del tracciato). */
    val id: String,
    /** Geometria completa: linee di partenza/traguardo/parziali e percorso. */
    val track: Track,
    val author: String,
    val publishedAt: Long,
    /** Numero di piloti con almeno un giro valido. */
    val drivers: Int,
    /** Prime posizioni della classifica (la completa si chiede a parte). */
    val topTimes: List<LeaderboardEntry>,
    /** Distanza dalla posizione dell'utente, se nota (metri). */
    val distanceM: Double? = null,
)

/** Riga di classifica: miglior giro di un pilota su un tracciato pubblicato. */
data class LeaderboardEntry(
    val rank: Int,
    val driver: String,
    val lapMs: Long,
    /** Tempi dei settori dello stesso giro (stesso ordine dei parziali). */
    val sectorsMs: List<Long>,
    val setAt: Long,
    /** Vero se la riga è dell'utente corrente. */
    val isMe: Boolean = false,
    /** Veicolo dichiarato per il giro. */
    val vehicle: String? = null,
    val lapId: String? = null,
)

/** Record di settore della community (il "viola" di tutti). */
data class CommunitySectorBest(val sectorIndex: Int, val driver: String, val timeMs: Long)

/** Giro da inviare alla classifica di un tracciato pubblicato. */
data class LapSubmission(
    val publishedTrackId: String,
    val lapMs: Long,
    val sectorsMs: List<Long>,
    val setAt: Long,
    val vehicle: String?,
)

/** Criterio di ricerca dei tracciati pubblicati. */
sealed class TrackQuery {
    /** Tracciati entro [radiusKm] dalla posizione (dal più vicino). */
    data class Nearby(val lat: Double, val lon: Double, val radiusKm: Double = 50.0) : TrackQuery()
    /** Nome del tracciato (contiene, maiuscole indifferenti). */
    data class ByName(val text: String) : TrackQuery()
    /** Pilota: tracciati pubblicati o corsi dal nickname indicato. */
    data class ByDriver(val nickname: String) : TrackQuery()
    /** Tutti (più recenti per primi). */
    data object All : TrackQuery()
}

/** Serializzazione JSON dei modelli della community (formato del server). */
object CommunityJson {

    fun entry(e: LeaderboardEntry): JSONObject = JSONObject()
        .put("rank", e.rank)
        .put("driver", e.driver)
        .put("lapMs", e.lapMs)
        .put("sectorsMs", JSONArray(e.sectorsMs))
        .put("setAt", e.setAt)
        .put("vehicle", e.vehicle ?: JSONObject.NULL)
        .put("lapId", e.lapId ?: JSONObject.NULL)

    fun parseEntry(o: JSONObject, myNickname: String? = null): LeaderboardEntry {
        val driver = o.getString("driver")
        return LeaderboardEntry(
            rank = o.getInt("rank"),
            driver = driver,
            lapMs = o.getLong("lapMs"),
            sectorsMs = o.getJSONArray("sectorsMs").let { a -> List(a.length()) { a.getLong(it) } },
            setAt = o.getLong("setAt"),
            isMe = myNickname != null && Nickname.key(driver) == Nickname.key(myNickname),
            vehicle = Json.optStr(o, "vehicle"),
            lapId = Json.optStr(o, "lapId"),
        )
    }

    fun publishedTrack(p: PublishedTrack): JSONObject = JSONObject()
        .put("id", p.id)
        .put("track", Json.track(p.track))
        .put("author", p.author)
        .put("publishedAt", p.publishedAt)
        .put("drivers", p.drivers)
        .put("topTimes", JSONArray().apply { p.topTimes.forEach { put(entry(it)) } })
        .put("distanceM", p.distanceM ?: JSONObject.NULL)

    fun parsePublishedTrack(o: JSONObject, myNickname: String? = null) = PublishedTrack(
        id = o.getString("id"),
        track = Json.parseTrack(o.getJSONObject("track")),
        author = o.getString("author"),
        publishedAt = o.getLong("publishedAt"),
        drivers = o.getInt("drivers"),
        topTimes = o.getJSONArray("topTimes").let { a -> List(a.length()) { parseEntry(a.getJSONObject(it), myNickname) } },
        distanceM = if (o.isNull("distanceM")) null else o.getDouble("distanceM"),
    )

    fun lap(l: LapSubmission): JSONObject = JSONObject()
        .put("publishedTrackId", l.publishedTrackId)
        .put("lapMs", l.lapMs)
        .put("sectorsMs", JSONArray(l.sectorsMs))
        .put("setAt", l.setAt)
        .put("vehicle", l.vehicle ?: JSONObject.NULL)

    fun parseLap(o: JSONObject) = LapSubmission(
        publishedTrackId = o.getString("publishedTrackId"),
        lapMs = o.getLong("lapMs"),
        sectorsMs = o.getJSONArray("sectorsMs").let { a -> List(a.length()) { a.getLong(it) } },
        setAt = o.getLong("setAt"),
        vehicle = Json.optStr(o, "vehicle"),
    )

    /** Giro di una sessione conclusa, pronto per l'invio. */
    fun lapFromSession(publishedTrackId: String, s: Session): LapSubmission? {
        val r = s.result ?: return null
        val total = r.elapsedMs ?: return null
        if (s.status != SessionStatus.FINISHED) return null
        return LapSubmission(
            publishedTrackId = publishedTrackId,
            lapMs = total,
            sectorsMs = Sectors.durations(r.splits, total),
            setAt = r.finishTime ?: s.startedAt,
            vehicle = s.vehicle,
        )
    }
}
