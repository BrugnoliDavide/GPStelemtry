package com.gpstelemetry.app

import com.gpstelemetry.core.LeaderboardEntry
import com.gpstelemetry.core.PublishedTrack
import com.gpstelemetry.core.Session
import com.gpstelemetry.core.Track

/**
 * Sorgente dati della scheda Esplora (tracciati pubblicati e classifiche).
 *
 * PREDISPOSIZIONE: oggi è attiva solo [NotConfiguredExploreRepository], che
 * non fa chiamate di rete e segnala che il servizio non è disponibile. Per
 * attivare la community si implementa questa interfaccia (es. su un backend
 * REST/Firebase) e la si assegna a [Community.repository] in GpsApp.
 *
 * Le callback sono invocate su un thread qualsiasi: chi le usa riporta il
 * risultato sul thread principale.
 */
interface ExploreRepository {

    /** Falso finché la community non è configurata (UI "In arrivo"). */
    val isAvailable: Boolean

    /** Tracciati pubblicati, opzionalmente vicini a una posizione o filtrati per testo. */
    fun listTracks(
        query: String? = null,
        nearLat: Double? = null,
        nearLon: Double? = null,
        callback: (Result<List<PublishedTrack>>) -> Unit,
    )

    /** Classifica completa di un tracciato pubblicato. */
    fun leaderboard(publishedId: String, callback: (Result<List<LeaderboardEntry>>) -> Unit)

    /** Pubblica un proprio tracciato. */
    fun publishTrack(track: Track, callback: (Result<PublishedTrack>) -> Unit)

    /** Invia un giro concluso alla classifica del tracciato pubblicato. */
    fun submitLap(publishedId: String, session: Session, callback: (Result<LeaderboardEntry>) -> Unit)
}

/** Nessun servizio configurato: nessuna rete, elenco vuoto. */
object NotConfiguredExploreRepository : ExploreRepository {
    override val isAvailable = false

    private fun <T> unavailable(): Result<T> =
        Result.failure(UnsupportedOperationException("Community non ancora disponibile"))

    override fun listTracks(
        query: String?,
        nearLat: Double?,
        nearLon: Double?,
        callback: (Result<List<PublishedTrack>>) -> Unit,
    ) = callback(Result.success(emptyList()))

    override fun leaderboard(publishedId: String, callback: (Result<List<LeaderboardEntry>>) -> Unit) =
        callback(unavailable())

    override fun publishTrack(track: Track, callback: (Result<PublishedTrack>) -> Unit) =
        callback(unavailable())

    override fun submitLap(publishedId: String, session: Session, callback: (Result<LeaderboardEntry>) -> Unit) =
        callback(unavailable())
}

/** Punto unico di accesso alla community. */
object Community {
    @Volatile
    var repository: ExploreRepository = NotConfiguredExploreRepository
}
