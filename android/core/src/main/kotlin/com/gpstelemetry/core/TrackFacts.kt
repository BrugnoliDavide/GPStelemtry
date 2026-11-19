package com.gpstelemetry.core

/**
 * Dati descrittivi di un tracciato (per la scheda del tracciato pubblicato):
 * lunghezza, dislivello, quote, settori.
 */
data class TrackFacts(
    /** Lunghezza del percorso registrato (m); null senza percorso. */
    val lengthM: Double?,
    /** D+ e D− (m) con isteresi; null se il percorso non ha la quota. */
    val elevGainM: Double?,
    val elevLossM: Double?,
    val minEleM: Double?,
    val maxEleM: Double?,
    /** Settori cronometrati (neutralizzazioni escluse). */
    val timedSectors: Int,
    val neutralZones: Int,
) {
    companion object {
        /** Variazioni di quota sotto questa soglia sono rumore del GPS. */
        const val ELEV_THRESHOLD_M = 3.0

        fun of(t: Track): TrackFacts {
            val eles = t.path.mapNotNull { it.ele }
            var gain: Double? = null
            var loss: Double? = null
            if (eles.size >= 2) {
                var anchor = eles.first()
                var g = 0.0
                var l = 0.0
                for (e in eles.drop(1)) {
                    val d = e - anchor
                    if (d >= ELEV_THRESHOLD_M) { g += d; anchor = e } else if (d <= -ELEV_THRESHOLD_M) { l += -d; anchor = e }
                }
                gain = g
                loss = l
            }
            val mask = Neutral.sectorMask(t.splits)
            return TrackFacts(
                lengthM = if (t.path.size >= 2) Geo.pathLength(t.path) else null,
                elevGainM = gain,
                elevLossM = loss,
                minEleM = eles.minOrNull(),
                maxEleM = eles.maxOrNull(),
                timedSectors = mask.count { !it },
                neutralZones = Neutral.count(t.splits),
            )
        }
    }
}

/**
 * Migliori settori calcolati da una classifica: per ogni settore
 * cronometrato il tempo più basso tra i giri in classifica (i record dei
 * piloti), il giro ideale e, se l'utente è in classifica, i suoi settori.
 */
data class SectorBoard(val rows: List<Row>, val idealLapMs: Long?, val myLapMs: Long?) {

    data class Row(
        /** Posizione nel giro (indice di sectorsMs). */
        val index: Int,
        /** Etichetta breve: "S1", "S2"… */
        val label: String,
        /** Nome del settore (dal parziale che lo chiude), se significativo. */
        val name: String?,
        val bestMs: Long?,
        val bestDriver: String?,
        val myMs: Long?,
    ) {
        /** Mio tempo meno il migliore (0 = settore viola). */
        val gapMs: Long? get() = if (myMs != null && bestMs != null) myMs - bestMs else null
    }

    companion object {
        fun of(t: Track, board: List<LeaderboardEntry>): SectorBoard {
            val mask = Neutral.sectorMask(t.splits)
            val labels = Neutral.sectorLabels(mask)
            val n = mask.size
            // Solo i giri con tutti i settori (sul server oltre 10 settori non ci sono).
            val full = board.filter { it.sectorsMs.size == n }
            val me = full.find { it.isMe }
            val rows = (0 until n).filter { !mask[it] }.map { k ->
                val best = full.filter { it.sectorsMs[k] > 0 }.minByOrNull { it.sectorsMs[k] }
                Row(
                    index = k,
                    label = labels[k],
                    name = Speech.sectorName(t.splits, k),
                    bestMs = best?.sectorsMs?.get(k),
                    bestDriver = best?.driver,
                    myMs = me?.sectorsMs?.get(k)?.takeIf { it > 0 },
                )
            }
            val ideal = if (rows.isNotEmpty() && rows.all { it.bestMs != null }) rows.sumOf { it.bestMs!! } else null
            return SectorBoard(rows, ideal, me?.lapMs)
        }
    }
}
