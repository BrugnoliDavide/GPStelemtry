package com.gpstelemetry.core

/**
 * Analisi dei SETTORI di un giro e confronto con lo storico.
 *
 * Un tracciato con N parziali ha N+1 settori:
 *   partenza → P1, P1 → P2, …, Pn → traguardo.
 * Senza parziali c'è un solo settore (l'intero giro).
 *
 * Valutazione di un tempo rispetto alle sessioni precedenti:
 *  - [Rating.BEST]   migliore di sempre (viola);
 *  - [Rating.BETTER] migliore o pari alla media (verde);
 *  - [Rating.WORSE]  peggiore della media (rosso).
 * Il primo tempo in assoluto è per definizione il migliore di sempre.
 */
object Sectors {

    enum class Rating { BEST, BETTER, WORSE }

    /** Statistiche storiche di un singolo tempo (settore o giro). */
    data class Stat(val best: Long, val avg: Long, val count: Int)

    /** Riferimenti storici di un tracciato. */
    data class Benchmark(
        /** Una voce per settore; null se non ci sono dati per quel settore. */
        val sectors: List<Stat?>,
        val lap: Stat?,
    ) {
        companion object {
            fun empty(nSectors: Int) = Benchmark(List(nSectors) { null }, null)
        }
    }

    /** Numero di settori di un tracciato con [splitCount] parziali. */
    fun count(splitCount: Int): Int = splitCount + 1

    /**
     * Durate dei settori completati (ms). Con [totalMs] non nullo
     * (giro concluso) include anche l'ultimo settore fino al traguardo.
     */
    fun durations(splits: List<SplitTime>, totalMs: Long?): List<Long> {
        val out = mutableListOf<Long>()
        var prev = 0L
        for (s in splits) {
            out.add(s.elapsed - prev)
            prev = s.elapsed
        }
        if (totalMs != null) out.add(totalMs - prev)
        return out
    }

    /**
     * Calcola i riferimenti dai risultati di giri CONCLUSI dello stesso
     * tracciato. Vengono considerati solo i giri con lo stesso numero di
     * parziali del tracciato attuale (gli altri contribuiscono solo al giro).
     */
    fun benchmark(history: List<SessionResult>, splitCount: Int): Benchmark {
        val n = count(splitCount)
        val finished = history.filter {
            it.status == SessionStatus.FINISHED && it.elapsedMs != null && it.elapsedMs > 0
        }
        val laps = finished.mapNotNull { it.elapsedMs }
        val perSector = List(n) { mutableListOf<Long>() }
        for (r in finished) {
            if (r.splits.size != splitCount) continue
            durations(r.splits, r.elapsedMs).forEachIndexed { i, d ->
                if (i < n && d > 0) perSector[i].add(d)
            }
        }
        return Benchmark(
            sectors = perSector.map { stat(it) },
            lap = stat(laps),
        )
    }

    private fun stat(values: List<Long>): Stat? =
        if (values.isEmpty()) null
        else Stat(values.min(), values.sum() / values.size, values.size)

    /** Valuta un tempo rispetto alle statistiche storiche. */
    fun rate(timeMs: Long, ref: Stat?): Rating = when {
        ref == null -> Rating.BEST
        timeMs < ref.best -> Rating.BEST
        timeMs <= ref.avg -> Rating.BETTER
        else -> Rating.WORSE
    }

    /** Differenza dal miglior tempo (negativa = più veloce), null se assente. */
    fun deltaToBest(timeMs: Long, ref: Stat?): Long? = ref?.let { timeMs - it.best }
}
