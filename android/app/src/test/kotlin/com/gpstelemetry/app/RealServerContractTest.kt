package com.gpstelemetry.app

import com.gpstelemetry.core.DriverProfile
import com.gpstelemetry.core.Friend
import com.gpstelemetry.core.FriendsBoard
import com.gpstelemetry.core.Gate
import com.gpstelemetry.core.LapSubmission
import com.gpstelemetry.core.LeaderboardEntry
import com.gpstelemetry.core.NicknameCheck
import com.gpstelemetry.core.ProfileVisibility
import com.gpstelemetry.core.PublishedTrack
import com.gpstelemetry.core.SplitGate
import com.gpstelemetry.core.Track
import com.gpstelemetry.core.TrackQuery
import com.gpstelemetry.core.TrackType
import com.gpstelemetry.core.UserAccount
import com.gpstelemetry.core.VehicleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Contratto app ↔ SERVER VERO: l'app (HttpCommunityApi + modelli del core)
 * contro il server Go della cartella server/, avviato a parte.
 * Si esegue solo indicando il server, es.:
 *   ./gradlew :app:testDebugUnitTest --tests '*RealServerContractTest*' -PgpsTestServer=http://127.0.0.1:18080/v1
 * (usa un database di prova: crea e cancella i propri utenti).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RealServerContractTest {

    private val url: String? = System.getProperty("gpsTestServer") ?: System.getenv("GPS_TEST_SERVER")

    private fun <T> await(call: ((Result<T>) -> Unit) -> Unit): T {
        val latch = CountDownLatch(1)
        var out: Result<T>? = null
        call { out = it; latch.countDown() }
        assertTrue("timeout", latch.await(30, TimeUnit.SECONDS))
        return out!!.getOrThrow()
    }

    @Test
    fun contrattoCompleto() {
        assumeTrue("server di prova non indicato (-PgpsTestServer)", url != null)
        val api = HttpCommunityApi(url!!)
        val tag = (System.currentTimeMillis() % 100000).toString()

        // Nickname e account.
        assertTrue(await<NicknameCheck> { api.checkNickname("anna$tag", it) }.available)
        val anna = await<UserAccount> { api.registerNickname("anna$tag", null, it) }
        val bruno = await<UserAccount> { api.registerNickname("bruno$tag", null, it) }
        val taken = await<NicknameCheck> { api.checkNickname("ANNA$tag", it) }
        assertFalse(taken.available)
        assertTrue(taken.suggestions.isNotEmpty())

        // Tracciato con una neutralizzazione: settori S1, S2, [N], S3.
        val g = { lat: Double -> Gate(lat, 9.0, 0.0, 20.0) }
        val track = Track(
            "loc-$tag", "Contratto $tag", TrackType.CIRCULAR, 15.0, 0, Gate(45.0, 9.0, 90.0, 30.0), null,
            splits = mutableListOf(SplitGate("Esse", g(45.001)), SplitGate("N", g(45.002), neutral = true), SplitGate("Uscita", g(45.003))),
        )
        val p = await<PublishedTrack> { api.publishTrack(anna, track, it) }
        assertEquals(p.id, p.track.remoteId)
        assertTrue(p.track.splits[1].neutral)

        // Giri: settore neutro a 0, come li produce l'app.
        fun lap(s: List<Long>, t: VehicleType?) = LapSubmission(p.id, s.sum(), s, System.currentTimeMillis(), "Modello", t)
        val a1 = await<LeaderboardEntry> { api.submitLap(anna, lap(listOf(10_000, 10_000, 0, 10_000), VehicleType.KART), it) }
        assertEquals(1, a1.rank)
        val slower = await<LeaderboardEntry> { api.submitLap(anna, lap(listOf(11_000, 10_000, 0, 10_000), VehicleType.KART), it) }
        assertNull("giro più lento: non salvato", slower.lapId)
        val b1 = await<LeaderboardEntry> { api.submitLap(bruno, lap(listOf(9_000, 11_500, 0, 10_500), VehicleType.KART), it) }
        assertEquals(2, b1.rank)
        try {
            await<LeaderboardEntry> { api.submitLap(bruno, lap(listOf(9_000, 11_500, 500, 10_500), null), it) }
            error("il settore neutro con tempo va rifiutato")
        } catch (e: CommunityException) {
            assertEquals(400, e.status)
        }

        val board = await<List<LeaderboardEntry>> { api.leaderboard(p.id, bruno, it) }
        assertEquals(listOf("anna$tag", "bruno$tag"), board.map { it.driver })
        assertEquals(listOf(9_000L, 11_500L, 0L, 10_500L), board[1].sectorsMs)
        assertTrue(board[1].isMe)
        assertEquals(VehicleType.KART, board[0].vehicleType)

        // Ricerca.
        val near = await<List<PublishedTrack>> { api.searchTracks(TrackQuery.Nearby(45.0, 9.0, 1.0), null, it) }
        assertTrue(near.any { it.id == p.id && it.distanceM != null })
        assertTrue(await<List<PublishedTrack>> { api.searchTracks(TrackQuery.ByDriver("bruno$tag"), null, it) }.any { it.id == p.id })
        assertEquals(2, await<PublishedTrack> { api.getTrack(p.id, null, it) }.drivers)

        // Amici.
        val d = await<DriverProfile?> { api.findDriver("ANNA$tag", bruno, it) }!!
        assertEquals(anna.userId, d.userId)
        val fb = await<FriendsBoard> { api.friendsTimes(p.id, listOf(Friend(d.userId, "vecchio_nome")), bruno, it) }
        assertEquals("anna$tag", fb.friends[0].friend.nickname)
        assertEquals(-1000L, fb.friends[0].gapMs)
        assertEquals(2, fb.me!!.rank)

        // Profilo privato e foto.
        assertEquals(ProfileVisibility.PRIVATE, await<UserAccount> { api.updateProfile(anna, ProfileVisibility.PRIVATE, it) }.visibility)
        assertNull(await<DriverProfile?> { api.findDriver("anna$tag", bruno, it) })
        val avatar = await<String> { api.uploadAvatar(bruno, byteArrayOf(-1, -40, -1, -32, 0, 16), it) }
        assertTrue(avatar.contains("/v1/avatars/"))

        // Rinomina, ritiro dei giri, eliminazione.
        assertEquals("bruno.$tag", await<UserAccount> { api.registerNickname("bruno.$tag", bruno, it) }.nickname)
        await<Unit> { api.deleteLap(bruno, b1.lapId!!, it) }
        assertEquals(1, await<Int> { api.deleteAllLaps(anna, it) })
        await<Unit> { api.unpublishTrack(anna, p.id, it) }
        await<Unit> { api.deleteAccount(anna, it) }
        await<Unit> { api.deleteAccount(bruno, it) }
        assertTrue(await<NicknameCheck> { api.checkNickname("anna$tag", it) }.available)
    }
}
