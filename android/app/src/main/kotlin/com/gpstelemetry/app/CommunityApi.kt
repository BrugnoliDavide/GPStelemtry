package com.gpstelemetry.app

import com.gpstelemetry.core.LapSubmission
import com.gpstelemetry.core.LeaderboardEntry
import com.gpstelemetry.core.NicknameCheck
import com.gpstelemetry.core.ProfileVisibility
import com.gpstelemetry.core.PublishedTrack
import com.gpstelemetry.core.Track
import com.gpstelemetry.core.TrackQuery
import com.gpstelemetry.core.UserAccount

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

    /** Classifica completa (miglior giro di ogni pilota). */
    fun leaderboard(publishedId: String, me: UserAccount?, callback: (Result<List<LeaderboardEntry>>) -> Unit)
}

/** Errore con messaggio da mostrare all'utente. */
class CommunityException(message: String) : Exception(message)

/** Punto unico di accesso al server della community. */
object Community {
    @Volatile
    lateinit var api: CommunityApi

    val isReady get() = ::api.isInitialized
}
