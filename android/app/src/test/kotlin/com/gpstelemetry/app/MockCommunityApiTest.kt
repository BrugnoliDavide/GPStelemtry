package com.gpstelemetry.app

import androidx.test.core.app.ApplicationProvider
import com.gpstelemetry.core.Gate
import com.gpstelemetry.core.LapSubmission
import com.gpstelemetry.core.ProfileVisibility
import com.gpstelemetry.core.Track
import com.gpstelemetry.core.TrackQuery
import com.gpstelemetry.core.TrackType
import com.gpstelemetry.core.UserAccount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Regole del server simulato (le stesse che dovrà rispettare il server vero). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MockCommunityApiTest {

    private lateinit var api: MockCommunityApi

    @Before
    fun setUp() {
        api = MockCommunityApi(ApplicationProvider.getApplicationContext(), latencyMs = 0)
    }

    /** Chiamata asincrona → risultato sincrono (per i test). */
    private fun <T> await(call: ((Result<T>) -> Unit) -> Unit): Result<T> {
        val latch = CountDownLatch(1)
        var out: Result<T>? = null
        call { out = it; latch.countDown() }
        assertTrue("timeout", latch.await(5, TimeUnit.SECONDS))
        return out!!
    }

    private fun register(nick: String): UserAccount =
        await<UserAccount> { api.registerNickname(nick, null, it) }.getOrThrow()

    private fun track(name: String, lat: Double) =
        Track("local-$name", name, TrackType.CIRCULAR, 15.0, 0, Gate(lat, 9.0, 90.0, 30.0), null)

    @Test
    fun nicknameAvailability() {
        val taken = await { api.checkNickname("Marta", it) }.getOrThrow() // utente di esempio
        assertFalse(taken.available)
        assertTrue(taken.suggestions.isNotEmpty())
        assertFalse(await { api.checkNickname("admin", it) }.getOrThrow().available)
        assertFalse(await { api.checkNickname("x", it) }.getOrThrow().available)
        assertTrue(await { api.checkNickname("davide_rc", it) }.getOrThrow().available)

        val me = register("davide_rc")
        assertFalse(await { api.checkNickname("DAVIDE_RC", it) }.getOrThrow().available)
        // Rinomina dello stesso account.
        val renamed = await<UserAccount> { api.registerNickname("davide.gp", me, it) }.getOrThrow()
        assertEquals(me.userId, renamed.userId)
        assertTrue(await { api.checkNickname("davide_rc", it) }.getOrThrow().available)
        // Nickname già preso: registrazione rifiutata.
        assertTrue(await<UserAccount> { api.registerNickname("luca", null, it) }.isFailure)
    }

    @Test
    fun publishSearchAndLeaderboard() {
        val me = register("davide")
        val other = register("anna")
        val near = await { api.publishTrack(me, track("Pista del Lago", 45.0), it) }.getOrThrow()
        val far = await { api.publishTrack(other, track("Anello Nord", 46.0), it) }.getOrThrow()
        assertEquals("davide", near.author)

        // Vicini: solo quello entro 50 km, con distanza.
        val nearby = await { api.searchTracks(TrackQuery.Nearby(45.01, 9.0), me, it) }.getOrThrow()
        assertEquals(listOf(near.id), nearby.map { it.id })
        assertTrue(nearby[0].distanceM!! in 1000.0..1200.0)
        // Per nome.
        assertEquals(listOf(far.id), await { api.searchTracks(TrackQuery.ByName("nord"), me, it) }.getOrThrow().map { it.id })

        // Giri: classifica = miglior giro per pilota.
        fun lap(ms: Long, v: String) = LapSubmission(near.id, ms, listOf(ms / 2, ms - ms / 2), 0, v)
        await { api.submitLap(me, lap(62_000, "Kart"), it) }.getOrThrow()
        await { api.submitLap(me, lap(60_500, "Kart"), it) }.getOrThrow()
        val annaLap = await { api.submitLap(other, lap(59_000, "Moto"), it) }.getOrThrow()
        val board = await { api.leaderboard(near.id, me, it) }.getOrThrow()
        assertEquals(listOf("anna", "davide"), board.map { it.driver })
        assertEquals(60_500L, board[1].lapMs)
        assertTrue(board[1].isMe)
        assertEquals("Moto", board[0].vehicle)

        // Per pilota: anna compare su entrambi (pubblicato + corso).
        val byAnna = await { api.searchTracks(TrackQuery.ByDriver("ann"), me, it) }.getOrThrow()
        assertEquals(setOf(near.id, far.id), byAnna.map { it.id }.toSet())

        // Profilo privato: nome nascosto agli altri.
        await { api.updateProfile(other, ProfileVisibility.PRIVATE, it) }.getOrThrow()
        assertEquals("Pilota privato", await { api.leaderboard(near.id, me, it) }.getOrThrow()[0].driver)

        // Solo l'autore ritira giri e tracciati.
        assertTrue(await { api.deleteLap(me, annaLap.lapId!!, it) }.isFailure)
        assertTrue(await { api.unpublishTrack(other, near.id, it) }.isFailure)
        await { api.unpublishTrack(me, near.id, it) }.getOrThrow()
        assertTrue(await { api.searchTracks(TrackQuery.All, me, it) }.getOrThrow().none { it.id == near.id })
    }

    @Test
    fun invalidTokenRejected() {
        val me = register("davide")
        val forged = me.copy(token = "falso")
        assertTrue(await { api.publishTrack(forged, track("X", 45.0), it) }.isFailure)
    }
}
