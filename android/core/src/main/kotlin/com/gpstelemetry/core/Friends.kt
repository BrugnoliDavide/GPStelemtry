package com.gpstelemetry.core

import org.json.JSONArray
import org.json.JSONObject

/*
 * AMICI. La lista vive SOLO sul telefono: il server non sa chi è amico di
 * chi. Quando serve, l'app manda al server gli id degli amici e riceve i
 * loro tempi su un tracciato (vedi android/docs/API.md, "Amici").
 */

/**
 * Amico salvato sul telefono. [userId] è stabile anche se l'amico cambia
 * nickname; [nickname] è l'ultimo nome noto e viene aggiornato a ogni
 * risposta del server.
 */
data class Friend(val userId: String, val nickname: String)

/** Profilo pubblico di un pilota trovato per nickname. */
data class DriverProfile(
    val userId: String,
    val nickname: String,
    val avatarUrl: String? = null,
    val displayName: String? = null,
    /** Tracciati su cui ha un tempo (solo nei risultati della ricerca). */
    val tracks: Int? = null,
)

/** Tempo di un amico su un tracciato. */
data class FriendTime(
    val friend: Friend,
    /** Falso se l'account non esiste più o il profilo è diventato privato. */
    val found: Boolean,
    /** Miglior giro dell'amico, con la posizione nella classifica completa; null se non ha tempi. */
    val entry: LeaderboardEntry?,
    /** Suo tempo meno il mio (negativo = è più veloce); null se manca uno dei due. */
    val gapMs: Long?,
)

/** Risposta "tempi degli amici" su un tracciato. */
data class FriendsBoard(
    /** Piloti nella classifica completa (per leggere la posizione: 3ª su 12). */
    val drivers: Int,
    /** Il mio miglior giro, se ne ho uno. */
    val me: LeaderboardEntry?,
    /** Amici con tempo dal più veloce, poi quelli senza tempo. */
    val friends: List<FriendTime>,
)

/** Regole della lista amici (pure: la memorizzazione è nell'app). */
object Friends {
    /** Amici al massimo (lo stesso limite per richiesta del server). */
    const val MAX = 200

    /** Ordine di visualizzazione: per nome, maiuscole indifferenti. */
    private fun sorted(list: List<Friend>) = list.sortedBy { Nickname.key(it.nickname) }

    /** Aggiunge o aggiorna un amico (stesso userId = stessa persona). */
    fun add(list: List<Friend>, f: Friend): List<Friend> {
        val rest = list.filter { it.userId != f.userId }
        require(rest.size < MAX) { "Al massimo $MAX amici" }
        return sorted(rest + f)
    }

    fun remove(list: List<Friend>, userId: String): List<Friend> = list.filter { it.userId != userId }

    /** Trova un amico dal nickname (maiuscole indifferenti). */
    fun byNickname(list: List<Friend>, nickname: String): Friend? =
        list.find { Nickname.key(it.nickname) == Nickname.key(nickname) }

    /** Aggiorna i nomi degli amici che hanno cambiato nickname. */
    fun refreshNames(list: List<Friend>, board: FriendsBoard): List<Friend> {
        val names = board.friends.filter { it.found }.associate { it.friend.userId to it.friend.nickname }
        if (list.none { f -> names[f.userId]?.let { it != f.nickname } == true }) return list
        return sorted(list.map { f -> names[f.userId]?.let { f.copy(nickname = it) } ?: f })
    }

    fun toJson(list: List<Friend>): String = JSONArray().apply {
        list.forEach { put(JSONObject().put("userId", it.userId).put("nickname", it.nickname)) }
    }.toString()

    /** Lettura tollerante: voci rovinate o doppie vengono scartate. */
    fun parse(text: String?): List<Friend> {
        val arr = runCatching { JSONArray(text ?: return emptyList()) }.getOrNull() ?: return emptyList()
        val out = LinkedHashMap<String, Friend>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("userId").trim()
            val nick = o.optString("nickname").trim()
            if (id.isNotEmpty() && nick.isNotEmpty()) out[id] = Friend(id, nick)
        }
        return sorted(out.values.toList()).take(MAX)
    }

    /* ------------------------------ JSON server ------------------------- */

    fun requestBody(friends: List<Friend>, vehicleType: VehicleType? = null): JSONObject = JSONObject()
        .put("userIds", JSONArray(friends.map { it.userId }))
        .put("vehicleType", vehicleType?.name ?: JSONObject.NULL)

    fun parseDriver(o: JSONObject) = DriverProfile(
        userId = o.getString("userId"),
        nickname = o.getString("nickname"),
        avatarUrl = Json.optStr(o, "avatarUrl"),
        displayName = Json.optStr(o, "displayName"),
        tracks = if (o.has("tracks") && !o.isNull("tracks")) o.getInt("tracks") else null,
    )

    /** Risposta di GET /drivers?q= (ricerca dei piloti). */
    fun parseDrivers(a: org.json.JSONArray) = List(a.length()) { parseDriver(a.getJSONObject(it)) }

    /** Testo minimo per cercare: 1 carattere valido per un nickname. */
    fun searchable(text: String): String? =
        text.trim().takeIf { t -> t.isNotEmpty() && t.length <= 20 && t.all { it.isLetterOrDigit() && it.code < 128 || it == '_' || it == '.' } }

    /**
     * Parti del nickname da evidenziare: [start, end) della prima occorrenza
     * di [query] (maiuscole indifferenti), null se non c'è.
     */
    fun match(nickname: String, query: String): IntRange? {
        val q = query.trim()
        if (q.isEmpty()) return null
        val i = nickname.indexOf(q, ignoreCase = true)
        return if (i < 0) null else i until i + q.length
    }

    /**
     * Risposta di POST /tracks/{id}/friends. Il nome di un amico non più
     * trovato resta quello salvato sul telefono.
     */
    fun parseBoard(o: JSONObject, local: List<Friend>, myNickname: String?): FriendsBoard {
        val known = local.associateBy { it.userId }
        val arr = o.getJSONArray("friends")
        val friends = List(arr.length()) { i ->
            val f = arr.getJSONObject(i)
            val id = f.getString("userId")
            val nick = Json.optStr(f, "nickname") ?: known[id]?.nickname ?: "?"
            FriendTime(
                friend = Friend(id, nick),
                found = f.optBoolean("found", false),
                entry = if (f.isNull("entry")) null else CommunityJson.parseEntry(f.getJSONObject("entry"), myNickname),
                gapMs = if (f.isNull("gapMs")) null else f.getLong("gapMs"),
            )
        }
        return FriendsBoard(
            drivers = o.optInt("drivers", 0),
            me = if (o.isNull("me")) null else CommunityJson.parseEntry(o.getJSONObject("me"), myNickname),
            friends = friends,
        )
    }

    /**
     * Costruisce la risposta a partire da una classifica completa (usato dal
     * server simulato; il server vero fa lo stesso calcolo in SQL).
     * [entriesByUser]: miglior giro di ogni pilota per userId; [names]: nome
     * attuale dei piloti visibili (assenti = eliminati o privati).
     */
    fun board(
        friends: List<Friend>,
        entriesByUser: Map<String, LeaderboardEntry>,
        names: Map<String, String>,
        me: LeaderboardEntry?,
        drivers: Int,
    ): FriendsBoard {
        val timed = mutableListOf<FriendTime>()
        val untimed = mutableListOf<FriendTime>()
        for (f in friends.distinctBy { it.userId }.take(MAX)) {
            val name = names[f.userId]
            if (name == null) {
                untimed += FriendTime(f, found = false, entry = null, gapMs = null)
                continue
            }
            val e = entriesByUser[f.userId]
            val ft = FriendTime(Friend(f.userId, name), true, e, if (e != null && me != null) e.lapMs - me.lapMs else null)
            if (e != null) timed += ft else untimed += ft
        }
        return FriendsBoard(
            drivers = drivers,
            me = me,
            friends = timed.sortedBy { it.entry!!.rank } +
                untimed.sortedWith(compareBy({ !it.found }, { Nickname.key(it.friend.nickname) })),
        )
    }
}
