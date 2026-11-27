package com.gpstelemetry.core

/**
 * Nota vocale: un punto del tracciato con un testo ("frena al cartello",
 * "curva cieca a destra") che la voce legge quando il pilota ci passa.
 * Non conta nel tempo: cambiarla non azzera la classifica.
 */
data class TrackNote(val lat: Double, val lon: Double, val text: String) {
    val point: LatLon get() = LatLon(lat, lon)
}

object TrackNotes {
    const val MAX_NOTES = 50
    const val MAX_TEXT = 140

    /** Distanza entro cui la nota scatta (il GPS sbaglia di qualche metro). */
    const val TRIGGER_M = 25.0

    /** Oltre questa distanza la nota si riarma (al giro successivo si rilegge). */
    const val REARM_M = 75.0

    /** Testo pulito (spazi, lunghezza); null se vuoto. */
    fun clean(text: String): String? =
        text.trim().replace(Regex("\\s+"), " ").take(MAX_TEXT).takeIf { it.isNotEmpty() }

    /** Note valide, al massimo [MAX_NOTES], nell'ordine di percorrenza del percorso. */
    fun sanitize(notes: List<TrackNote>, path: List<PathPoint>): List<TrackNote> {
        val ok = notes.mapNotNull { n -> clean(n.text)?.let { n.copy(text = it) } }.take(MAX_NOTES)
        if (path.size < 2) return ok
        return ok.sortedBy { Geo.distanceAlongPath(path, it.point) ?: Double.MAX_VALUE }
    }
}

/**
 * Decide quando leggere le note durante la registrazione: una nota scatta
 * quando la traiettoria (il tratto tra due fix consecutivi, così non la si
 * salta anche a 200 km/h con un fix ogni mezzo secondo) passa entro
 * [TrackNotes.TRIGGER_M]; si riarma solo dopo essersi allontanati oltre
 * [TrackNotes.REARM_M], così ogni giro la si sente una volta.
 */
class NoteTrigger(private val notes: List<TrackNote>) {
    private val armed = BooleanArray(notes.size) { true }
    private var prev: LatLon? = null

    /** Note da leggere con questo fix (di solito nessuna). */
    fun feed(lat: Double, lon: Double): List<TrackNote> {
        if (notes.isEmpty()) return emptyList()
        val cur = LatLon(lat, lon)
        val from = prev ?: cur
        prev = cur
        val out = mutableListOf<TrackNote>()
        notes.forEachIndexed { i, n ->
            val d = Geo.distToSegment(n.point, from, cur)
            if (armed[i] && d <= TrackNotes.TRIGGER_M) {
                armed[i] = false
                out += n
            } else if (!armed[i] && Geo.haversine(n.point, cur) > TrackNotes.REARM_M) {
                armed[i] = true
            }
        }
        return out
    }
}
