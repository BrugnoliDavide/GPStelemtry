package com.gpstelemetry.core

/*
 * Modelli della COMMUNITY (scheda Esplora) — predisposizione.
 *
 * Descrivono i tracciati pubblicati dagli utenti e le loro classifiche.
 * Il backend non esiste ancora: l'app usa un repository "non configurato"
 * (vedi ExploreRepository nell'app) e la scheda mostra lo stato "In arrivo".
 * Quando ci sarà un servizio remoto basterà implementare il repository
 * restituendo questi oggetti.
 */

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
)

/** Record di settore della community (il "viola" di tutti). */
data class CommunitySectorBest(val sectorIndex: Int, val driver: String, val timeMs: Long)
