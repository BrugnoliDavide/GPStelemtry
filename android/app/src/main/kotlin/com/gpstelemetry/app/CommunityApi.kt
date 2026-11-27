package com.gpstelemetry.app

import com.gpstelemetry.core.DriverProfile
import com.gpstelemetry.core.Friend
import com.gpstelemetry.core.FriendsBoard
import com.gpstelemetry.core.LapSubmission
import com.gpstelemetry.core.LeaderboardEntry
import com.gpstelemetry.core.NicknameCheck
import com.gpstelemetry.core.ProfileVisibility
import com.gpstelemetry.core.PublishedTrack
import com.gpstelemetry.core.Track
import com.gpstelemetry.core.TrackQuery
import com.gpstelemetry.core.UserAccount
import com.gpstelemetry.core.VehicleType

/**
 * Operazioni dell'app verso il server della community.
 * Contratto HTTP corrispondente: android/docs/API.md.
 *
 * Oggi l'implementazione attiva è [MockCommunityApi] (server simulato sul
 * telefono). Per collegare il server vero basterà un'implementazione HTTP
 * di questa interfaccia, assegnata a [Community.api] in GpsApp.
 *
 * Tutte le chiamate sono asincrone: la callback arriva su un thread di
 * lavoro e chi la usa riporta il risultato sul thread principale.
 */
interface CommunityApi {

    /** Falso se il servizio non è raggiungibile/configurato. */
    val isAvailable: Boolean

    /** Vero per il server simulato (la UI lo segnala). */
    val isMock: Boolean

    /** Identifica il server: l'account e i collegamenti valgono solo per questo server. */
    val serverId: String

    /* ------------------------------ Utenti ------------------------------- */

    /** Verifica che il nickname sia valido e libero. */
    fun checkNickname(nickname: String, callback: (Result<NicknameCheck>) -> Unit)

    /**
     * Registra il nickname: crea l'account, oppure lo rinomina se [current]
     * non è nullo. Fallisce se nel frattempo è stato preso.
     */
    fun registerNickname(nickname: String, current: UserAccount?, callback: (Result<UserAccount>) -> Unit)

    /** Aggiorna le impostazioni pubbliche del profilo. */
    fun updateProfile(account: UserAccount, visibility: ProfileVisibility, callback: (Result<UserAccount>) -> Unit)

    /** Carica la foto profilo (JPEG); restituisce l'URL pubblico. */
    fun uploadAvatar(account: UserAccount, jpeg: ByteArray, callback: (Result<String>) -> Unit)

    /* ---------------------------- Tracciati ------------------------------ */

    /** Pubblica un tracciato personale. */
    fun publishTrack(account: UserAccount, track: Track, callback: (Result<PublishedTrack>) -> Unit)

    /** Ritira la pubblicazione (il tracciato resta sul telefono). */
    fun unpublishTrack(account: UserAccount, publishedId: String, callback: (Result<Unit>) -> Unit)

    /** Ricerca: vicini, per nome, per pilota o tutti. */
    fun searchTracks(query: TrackQuery, me: UserAccount?, callback: (Result<List<PublishedTrack>>) -> Unit)

    /** Dettaglio di un tracciato pubblicato (per importarlo e cronometrarsi). */
    fun getTrack(publishedId: String, me: UserAccount?, callback: (Result<PublishedTrack>) -> Unit)

    /* ---------------------------- Risultati ------------------------------ */

    /** Invia un giro alla classifica del tracciato pubblicato. */
    fun submitLap(account: UserAccount, lap: LapSubmission, callback: (Result<LeaderboardEntry>) -> Unit)

    /** Ritira un giro inviato. */
    fun deleteLap(account: UserAccount, lapId: String, callback: (Result<Unit>) -> Unit)

    /**
     * Classifica completa: miglior giro di ogni pilota. Con [vehicleType]
     * solo quella categoria (il record di ogni pilota in quella categoria).
     */
    fun leaderboard(
        publishedId: String,
        me: UserAccount?,
        vehicleType: VehicleType? = null,
        callback: (Result<List<LeaderboardEntry>>) -> Unit,
    )

    /* ------------------------------ Amici -------------------------------- */

    /** Pilota con profilo pubblico dal nickname; null se non esiste o è privato. */
    fun findDriver(nickname: String, me: UserAccount?, callback: (Result<DriverProfile?>) -> Unit)

    /**
     * Ricerca mentre si scrive: prima i nickname che iniziano con [text], poi
     * (da 2 caratteri) quelli che lo contengono. Mai profili privati né [me].
     */
    fun searchDrivers(text: String, me: UserAccount?, limit: Int = 8, callback: (Result<List<DriverProfile>>) -> Unit)

    /**
     * Tempi degli [friends] sul tracciato pubblicato. La lista amici è solo
     * sul telefono: viene inviata a ogni richiesta.
     */
    fun friendsTimes(publishedId: String, friends: List<Friend>, me: UserAccount?, callback: (Result<FriendsBoard>) -> Unit)

    /* ----------------------- Dati e account (predisposti) ---------------- */

    /** Elimina tutti i giri dell'utente da tutte le classifiche; restituisce quanti. */
    fun deleteAllLaps(account: UserAccount, callback: (Result<Int>) -> Unit)

    /**
     * Elimina il profilo: account, nickname (torna libero), foto, giri e
     * tracciati pubblicati. Irreversibile.
     */
    fun deleteAccount(account: UserAccount, callback: (Result<Unit>) -> Unit)
}

/**
 * Errore con messaggio da mostrare all'utente. [retryable]: server occupato
 * o non raggiungibile, l'operazione può essere ritentata più tardi.
 */
class CommunityException(
    message: String,
    val retryable: Boolean = false,
    /** Stato HTTP, se l'errore viene dal server vero. */
    val status: Int? = null,
) : Exception(message)

/** Punto unico di accesso al server della community. */
object Community {
    /** Server vero (Raspberry Pi, Tailscale Funnel). */
    const val SERVER_URL = "https://gpstelemetry.taile07802.ts.net/v1"

    @Volatile
    lateinit var api: CommunityApi

    val isReady get() = ::api.isInitialized

    /**
     * Collega il server scelto nelle impostazioni (vero o di prova). Se è
     * diverso da quello dell'ultima volta, i collegamenti locali
     * (tracciati pubblicati/importati, giri inviati) valgono per l'altro
     * server: vengono scollegati, i dati restano sul telefono.
     */
    fun connect(ctx: android.content.Context) {
        val app = ctx.applicationContext
        api = if (Settings.useTestServer(app)) MockCommunityApi(app) else HttpCommunityApi(SERVER_URL)
        val prefs = app.getSharedPreferences("community", android.content.Context.MODE_PRIVATE)
        val last = prefs.getString("server", null)
        if (last != api.serverId) {
            if (last != null || TrackStore.listTracks(app).any { it.remoteId != null }) unlinkAll(app)
            prefs.edit().putString("server", api.serverId).apply()
        }
    }

    /** Scollega tracciati e sessioni dal server precedente. */
    private fun unlinkAll(app: android.content.Context) {
        TrackStore.listTracks(app).filter { it.remoteId != null || it.published }.forEach {
            TrackStore.saveTrack(app, it.copy(published = false, remoteId = null, remoteAuthor = null))
        }
        TrackStore.listSessions(app).filter { it.remoteLapId != null }.forEach {
            it.remoteLapId = null
            TrackStore.saveSession(app, it)
        }
        LapQueue.clear(app)
    }
}
