package com.gpstelemetry.app

import com.gpstelemetry.core.DriverProfile
import com.gpstelemetry.core.NicknameCheck
import com.gpstelemetry.core.ProfileVisibility
import com.gpstelemetry.core.UserAccount
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Client HTTP: attese e nuovi tentativi, errori, forma delle richieste. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HttpCommunityApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: HttpCommunityApi
    private val waits = mutableListOf<Long>()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        api = HttpCommunityApi(server.url("/v1").toString(), maxAttempts = 3, sleep = { waits += it })
    }

    @After
    fun tearDown() = server.shutdown()

    private fun <T> await(call: ((Result<T>) -> Unit) -> Unit): Result<T> {
        val latch = CountDownLatch(1)
        var out: Result<T>? = null
        call { out = it; latch.countDown() }
        assertTrue("timeout", latch.await(10, TimeUnit.SECONDS))
        return out!!
    }

    private fun json(code: Int, body: String) = MockResponse().setResponseCode(code)
        .setHeader("Content-Type", "application/json").setBody(body)

    private val available = """{"nickname":"anna","available":true,"reason":null,"suggestions":[]}"""

    @Test
    fun serverOccupatoAttendeERiprova() {
        server.enqueue(json(503, """{"error":"busy","message":"Server occupato"}""").setHeader("Retry-After", "4"))
        server.enqueue(json(429, """{"error":"rate_limited","message":"Troppe richieste"}"""))
        server.enqueue(json(200, available))
        val r = await<NicknameCheck> { api.checkNickname("anna", it) }.getOrThrow()
        assertTrue(r.available)
        assertEquals(3, server.requestCount)
        // 4 s chiesti dal server, poi 2 s (secondo tentativo, senza Retry-After).
        assertEquals(listOf(4000L, 2000L), waits)
    }

    @Test
    fun dopoTroppiTentativiErroreRitentabile() {
        repeat(3) { server.enqueue(json(503, """{"error":"busy","message":"Server occupato, riprovo tra poco"}""")) }
        val e = await<NicknameCheck> { api.checkNickname("anna", it) }.exceptionOrNull() as CommunityException
        assertTrue(e.retryable)
        assertEquals("Server occupato, riprovo tra poco", e.message)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun erroreDelServerConMessaggioNonRitentabile() {
        server.enqueue(json(409, """{"error":"nickname_taken","message":"Già in uso"}"""))
        val e = await<UserAccount> { api.registerNickname("anna", null, it) }.exceptionOrNull() as CommunityException
        assertFalse(e.retryable)
        assertEquals(409, e.status)
        assertEquals("Già in uso", e.message)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun rinominaConPatchEFallbackSeAccountScaduto() {
        val current = UserAccount("u1", "anna", "tok-vecchio")
        server.enqueue(json(200, """{"userId":"u1","nickname":"anna.gp","visibility":"PUBLIC","avatarUrl":null}"""))
        val renamed = await<UserAccount> { api.registerNickname("anna.gp", current, it) }.getOrThrow()
        assertEquals("anna.gp", renamed.nickname)
        assertEquals("tok-vecchio", renamed.token) // PATCH non restituisce il token
        val req = server.takeRequest()
        assertEquals("PATCH", req.method)
        assertEquals("/v1/users/me", req.path)
        assertEquals("Bearer tok-vecchio", req.getHeader("Authorization"))

        // Token non più valido: si crea un account nuovo.
        server.enqueue(json(401, """{"error":"unauthorized","message":"Accesso necessario"}"""))
        server.enqueue(json(201, """{"userId":"u2","nickname":"anna","token":"tok-nuovo","visibility":"PUBLIC","avatarUrl":null}"""))
        val created = await<UserAccount> { api.registerNickname("anna", current, it) }.getOrThrow()
        assertEquals("tok-nuovo", created.token)
        server.takeRequest()
        assertEquals("POST", server.takeRequest().method)
    }

    @Test
    fun pilotaNonTrovatoENicknameNelPercorso() {
        server.enqueue(json(404, """{"error":"not_found","message":"Pilota non trovato"}"""))
        assertNull(await<DriverProfile?> { api.findDriver("luca b", null, it) }.getOrThrow())
        assertEquals("/v1/drivers/luca%20b", server.takeRequest().path)
    }

    @Test
    fun visibilitaEFotoProfilo() {
        val acc = UserAccount("u1", "anna", "tok")
        server.enqueue(json(200, """{"userId":"u1","nickname":"anna","visibility":"PRIVATE","avatarUrl":null}"""))
        assertEquals(ProfileVisibility.PRIVATE, await<UserAccount> { api.updateProfile(acc, ProfileVisibility.PRIVATE, it) }.getOrThrow().visibility)
        server.enqueue(json(200, """{"avatarUrl":"https://x/v1/avatars/a.jpg"}"""))
        assertEquals("https://x/v1/avatars/a.jpg", await<String> { api.uploadAvatar(acc, byteArrayOf(-1, -40, -1), it) }.getOrThrow())
        server.takeRequest()
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals("image/jpeg", put.getHeader("Content-Type"))
    }
}
