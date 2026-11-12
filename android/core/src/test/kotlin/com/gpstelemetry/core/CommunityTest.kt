package com.gpstelemetry.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommunityTest {

    @Test
    fun nicknameRules() {
        assertNull(Nickname.validate("Marta_92"))
        assertNull(Nickname.validate("luca.b"))
        assertNotNull(Nickname.validate("ab"))
        assertNotNull(Nickname.validate("a".repeat(21)))
        assertNotNull(Nickname.validate("con spazio"))
        assertNotNull(Nickname.validate("àccento"))
        assertNotNull(Nickname.validate(".punto"))
        assertEquals(Nickname.key("Marta"), Nickname.key(" marta "))
    }

    @Test
    fun publishedTrackRoundTrip() {
        val track = Track(
            "t1", "Pista", TrackType.CIRCULAR, 15.0, 1, Gate(45.0, 9.0, 90.0, 40.0), null,
            published = true, remoteId = "p1",
        )
        val p = PublishedTrack(
            "p1", track, "Marta", 10, 2,
            listOf(LeaderboardEntry(1, "Marta", 60_000, listOf(30_000, 30_000), 5, vehicle = "Kart", lapId = "l1")),
            1200.0,
        )
        val back = CommunityJson.parsePublishedTrack(CommunityJson.publishedTrack(p), myNickname = "marta")
        assertEquals("Pista", back.track.name)
        assertTrue(back.track.published)
        assertEquals("p1", back.track.remoteId)
        assertEquals("Kart", back.topTimes[0].vehicle)
        assertTrue(back.topTimes[0].isMe)
        assertEquals(1200.0, back.distanceM)
    }

    @Test
    fun lapFromFinishedSession() {
        val s = Session("s", "t", "n", 0, TrackType.CIRCULAR, Gate(0.0, 0.0), null, emptyList(), vehicle = "Moto")
        s.status = SessionStatus.FINISHED
        s.result = SessionResult(SessionStatus.FINISHED, 0, 50_000, 50_000, listOf(SplitTime("P1", 20_000, 20_000)), 0, 0)
        val lap = CommunityJson.lapFromSession("p1", s)!!
        assertEquals(listOf(20_000L, 30_000L), lap.sectorsMs)
        assertEquals("Moto", lap.vehicle)
        assertEquals(lap, CommunityJson.parseLap(CommunityJson.lap(lap)))
    }

    @Test
    fun oldTrackJsonStillParses() {
        val t = Track("t", "x", TrackType.OPEN, 10.0, 0, Gate(1.0, 2.0), Gate(1.1, 2.1))
        val o = Json.track(t).apply { remove("published"); remove("remoteId"); remove("remoteAuthor") }
        val back = Json.parseTrack(o)
        assertEquals(false, back.published)
        assertNull(back.remoteId)
    }
}
