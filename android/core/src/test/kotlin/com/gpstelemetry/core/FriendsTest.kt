package com.gpstelemetry.core

import org.json.JSONObject
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

class FriendsTest {

    private fun entry(rank: Int, driver: String, ms: Long) = LeaderboardEntry(rank, driver, ms, listOf(ms), 0)

    @Test
    fun aggiuntaSenzaDoppioniEOrdinata() {
        var l = Friends.add(emptyList(), Friend("2", "luca"))
        l = Friends.add(l, Friend("1", "Anna"))
        l = Friends.add(l, Friend("2", "luca.gp")) // stessa persona, nome nuovo
        assertEquals(listOf("Anna", "luca.gp"), l.map { it.nickname })
        assertEquals("1", Friends.byNickname(l, "ANNA")?.userId)
        assertEquals(listOf("2"), Friends.remove(l, "1").map { it.userId })
    }

    @Test
    fun salvataggioERiletturaTollerante() {
        val l = listOf(Friend("1", "anna"), Friend("2", "bruno"))
        assertEquals(l, Friends.parse(Friends.toJson(l)))
        assertEquals(emptyList<Friend>(), Friends.parse("non json"))
        assertEquals(emptyList<Friend>(), Friends.parse(null))
        assertEquals(1, Friends.parse("""[{"userId":"1","nickname":"a"},{"userId":"1","nickname":"a"},{"x":1}]""").size)
    }

    @Test
    fun tempiDegliAmici() {
        val me = entry(3, "io", 31_000)
        val friends = listOf(Friend("a", "anna"), Friend("b", "bruno"), Friend("d", "dario"), Friend("x", "ex"))
        val board = Friends.board(
            friends,
            entriesByUser = mapOf("a" to entry(2, "anna", 30_000), "b" to entry(5, "bruno", 33_000)),
            names = mapOf("a" to "anna.gp", "b" to "bruno", "d" to "dario"),
            me = me,
            drivers = 6,
        )
        assertEquals(listOf("anna.gp", "bruno", "dario", "ex"), board.friends.map { it.friend.nickname })
        assertEquals(-1000L, board.friends[0].gapMs)
        assertEquals(2000L, board.friends[1].gapMs)
        assertNull(board.friends[2].entry)
        assertTrue(board.friends[2].found)
        assertFalse(board.friends[3].found)
        // I nomi cambiati si aggiornano nella lista locale.
        assertEquals("anna.gp", Friends.refreshNames(friends, board).first().nickname)
    }

    @Test
    fun rispostaDelServer() {
        val json = JSONObject(
            """{"trackId":"t","drivers":4,"me":{"rank":3,"driver":"io","lapMs":31000,"sectorsMs":[31000],"setAt":1},
            "friends":[{"userId":"a","nickname":"anna.gp","found":true,"gapMs":-1000,
                        "entry":{"rank":2,"driver":"anna.gp","lapMs":30000,"sectorsMs":[30000],"setAt":2}},
                       {"userId":"x","nickname":null,"found":false,"entry":null,"gapMs":null}]}"""
        )
        val b = Friends.parseBoard(json, listOf(Friend("x", "vecchio_nome")), "io")
        assertEquals(4, b.drivers)
        assertTrue(b.me!!.isMe)
        assertEquals("anna.gp", b.friends[0].friend.nickname)
        assertEquals(2, b.friends[0].entry!!.rank)
        assertEquals("vecchio_nome", b.friends[1].friend.nickname)
        assertEquals(
            """{"userIds":["a"],"vehicleType":"KART"}""",
            Friends.requestBody(listOf(Friend("a", "anna")), VehicleType.KART).toString(),
        )
    }

    @Test
    fun ricercaEEvidenziazione() {
        assertEquals("an", Friends.searchable("  an "))
        assertEquals(null, Friends.searchable(""))
        assertEquals(null, Friends.searchable("a%"))
        assertEquals(null, Friends.searchable("è"))
        assertEquals(0..1, Friends.match("Anna_RC", "an"))
        assertEquals(3..4, Friends.match("marianna", "IA"))
        assertEquals(null, Friends.match("bruno", "an"))
        val d = Friends.parseDriver(org.json.JSONObject("""{"userId":"u","nickname":"anna","displayName":"Anna R.","avatarUrl":null,"tracks":3}"""))
        assertEquals(DriverProfile("u", "anna", null, "Anna R.", 3), d)
    }
}
