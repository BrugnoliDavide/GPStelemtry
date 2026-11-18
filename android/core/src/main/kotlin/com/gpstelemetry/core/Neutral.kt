package com.gpstelemetry.core

/**
 * NEUTRALIZZAZIONI: settori speciali in cui il tempo non scorre (es. tratti di
 * trasferimento, pit lane, attraversamenti).
 *
 * Una neutralizzazione è a tutti gli effetti un settore del tracciato:
 * inizia su un parziale contrassegnato [SplitGate.neutral] (ingresso) e
 * finisce sul parziale successivo (uscita). Nel mezzo il cronometro è fermo;
 * la durata reale viene comunque memorizzata ([SplitTime.neutralMs]) ma non
 * è mostrata come tempo né sommata al giro.
 *
 * Con N parziali i settori sono N+1 (vedi [Sectors]); il settore k è
 * neutralizzato se il parziale k-1 è un ingresso valido.
 */
object Neutral {

    /** Il parziale [i] apre una neutralizzazione (serve un parziale dopo, l'uscita). */
    fun isEntry(splits: List<SplitGate>, i: Int): Boolean =
        i in splits.indices && splits[i].neutral && i < splits.lastIndex

    /** Il parziale [i] chiude una neutralizzazione. */
    fun isExit(splits: List<SplitGate>, i: Int): Boolean = isEntry(splits, i - 1)

    /** Il parziale [i] è un estremo (ingresso o uscita) di una neutralizzazione. */
    fun isBoundary(splits: List<SplitGate>, i: Int): Boolean = isEntry(splits, i) || isExit(splits, i)

    /** Per ogni settore del tracciato: vero se neutralizzato. */
    fun sectorMask(splits: List<SplitGate>): List<Boolean> =
        List(Sectors.count(splits.size)) { k -> isEntry(splits, k - 1) }

    /** Per ogni settore di un risultato registrato: vero se neutralizzato. */
    fun sectorMask(times: List<SplitTime>, sectorCount: Int): List<Boolean> =
        List(sectorCount) { k -> times.getOrNull(k)?.neutral == true }

    /** Numero di neutralizzazioni del tracciato. */
    fun count(splits: List<SplitGate>): Int = splits.indices.count { isEntry(splits, it) }

    /** Rimuove i contrassegni non validi (ultimo parziale come ingresso). */
    fun sanitize(splits: List<SplitGate>): List<SplitGate> =
        splits.mapIndexed { i, s -> if (s.neutral && !isEntry(splits, i)) s.copy(neutral = false) else s }

    /**
     * Etichette brevi delle linee intermedie: "P1", "P2", … per i parziali
     * normali; "N1" / "N1 fine" per ingresso e uscita delle neutralizzazioni.
     */
    fun gateLabels(splits: List<SplitGate>): List<String> {
        var p = 0
        var n = 0
        return splits.indices.map { i ->
            when {
                isEntry(splits, i) -> "N${++n}"
                isExit(splits, i) -> "N$n fine"
                else -> "P${++p}"
            }
        }
    }

    /**
     * Etichette brevi dei settori: "S1", "S2", … per i settori cronometrati,
     * "N1", "N2", … per le neutralizzazioni.
     */
    fun sectorLabels(mask: List<Boolean>): List<String> {
        var s = 0
        var n = 0
        return mask.map { if (it) "N${++n}" else "S${++s}" }
    }

    /**
     * Posizione in cui inserire un nuovo parziale nel punto [p] perché la
     * sequenza segua l'ordine di percorrenza del percorso registrato.
     * Senza percorso il parziale va in coda.
     */
    fun insertIndex(splits: List<SplitGate>, path: List<PathPoint>, p: LatLon): Int {
        val along = Geo.distanceAlongPath(path, p) ?: return splits.size
        val idx = splits.indexOfFirst { s ->
            (Geo.distanceAlongPath(path, s.gate.center) ?: Double.MAX_VALUE) > along
        }
        return if (idx < 0) splits.size else idx
    }

    /**
     * Aggiunge una neutralizzazione (ingresso [entry] → uscita [exit]) nella
     * posizione giusta lungo il percorso. Restituisce l'indice dell'ingresso.
     * Se l'uscita precede l'ingresso lungo il percorso, i due vengono scambiati.
     */
    fun addZone(
        splits: MutableList<SplitGate>,
        path: List<PathPoint>,
        entry: Gate,
        exit: Gate,
        number: Int,
    ): Int {
        var a = entry
        var b = exit
        val da = Geo.distanceAlongPath(path, a.center)
        val db = Geo.distanceAlongPath(path, b.center)
        if (da != null && db != null && db < da) { a = exit; b = entry }
        val i = insertIndex(splits, path, a.center)
        splits.add(i, SplitGate("Neutralizzazione $number", a, neutral = true))
        splits.add(i + 1, SplitGate("Fine neutralizzazione $number", b))
        return i
    }

    /**
     * Tratti del percorso compresi nelle neutralizzazioni (dall'ingresso
     * all'uscita), per evidenziarli in giallo sulla mappa.
     */
    fun stretches(splits: List<SplitGate>, path: List<PathPoint>): List<List<LatLon>> {
        if (path.size < 2) return emptyList()
        val cum = DoubleArray(path.size)
        for (i in 1 until path.size) {
            cum[i] = cum[i - 1] + Geo.haversine(path[i - 1].lat, path[i - 1].lon, path[i].lat, path[i].lon)
        }
        return splits.indices.filter { isEntry(splits, it) }.mapNotNull { i ->
            val a = splits[i].gate.center
            val b = splits[i + 1].gate.center
            val da = Geo.distanceAlongPath(path, a) ?: return@mapNotNull null
            val db = Geo.distanceAlongPath(path, b) ?: return@mapNotNull null
            if (db <= da) return@mapNotNull null
            listOf(a) + path.indices.filter { cum[it] > da && cum[it] < db }.map { LatLon(path[it].lat, path[it].lon) } + b
        }
    }

    /** Tempo reale totale (ms) del giro, neutralizzazioni comprese. */
    fun rawElapsed(r: SessionResult): Long? = r.elapsedMs?.let { it + r.neutralMs }
}
