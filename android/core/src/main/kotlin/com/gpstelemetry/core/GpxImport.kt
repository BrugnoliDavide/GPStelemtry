package com.gpstelemetry.core

import org.w3c.dom.Element
import org.w3c.dom.NodeList
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Importazione di un tracciato da file GPX.
 *
 *  - GPX esportati da GPSTelemetry: ricostruzione identica (tipo,
 *    tolleranza, linee con orientamento e larghezza, nomi dei parziali).
 *  - GPX generici (altre app, Strava, Garmin, …): il percorso viene dalla
 *    traccia (trk) o dalla rotta (rte); partenza/traguardo dai waypoint
 *    "Start/Partenza", "Finish/Traguardo/Arrivo" (gli altri waypoint
 *    diventano parziali) oppure dal primo/ultimo punto. Le linee senza
 *    orientamento sono messe perpendicolari al percorso. Il tracciato è
 *    circolare se inizio e fine coincidono (entro [LOOP_DISTANCE_M]).
 */
object GpxImport {

    /** Distanza massima tra inizio e fine per considerare il tracciato un circuito. */
    const val LOOP_DISTANCE_M = 60.0
    const val DEFAULT_TOLERANCE = 15.0
    /** Oltre questo numero di punti il percorso viene semplificato. */
    private const val MAX_PATH_POINTS = 3000

    class ImportException(message: String) : Exception(message)

    private val START_NAMES = setOf("start", "partenza", "via", "start/finish", "start / finish")
    private val FINISH_NAMES = setOf("finish", "traguardo", "arrivo", "end", "fine")

    /** Converte il contenuto di un file GPX in un nuovo tracciato. */
    fun parse(xml: ByteArray, newId: String, now: Long): Result<Track> = runCatching {
        val doc = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            // Nessuna entità esterna o DTD (file da fonti non fidate).
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { isExpandEntityReferences = false }
        }.newDocumentBuilder().parse(ByteArrayInputStream(xml))
        val root = doc.documentElement
        if (root == null || localName(root) != "gpx") throw ImportException("Il file non è un GPX valido")

        // Percorso: traccia, altrimenti rotta.
        var path = points(root.getElementsByTagName("trkpt"))
        if (path.size < 2) path = points(root.getElementsByTagName("rtept"))

        // Metadati GPSTelemetry (se presenti).
        val meta = elements(root.getElementsByTagName("gpstel:track")).firstOrNull()
        val tolerance = meta?.getAttribute("tolerance")?.toDoubleOrNull()?.coerceIn(5.0, 100.0) ?: DEFAULT_TOLERANCE
        val metaType = meta?.getAttribute("type")?.let { runCatching { TrackType.valueOf(it) }.getOrNull() }

        // Waypoint → linee.
        var start: Gate? = null
        var finish: Gate? = null
        val splits = mutableListOf<SplitGate>()
        for (w in elements(root.getElementsByTagName("wpt"))) {
            val lat = w.getAttribute("lat").toDoubleOrNull() ?: continue
            val lon = w.getAttribute("lon").toDoubleOrNull() ?: continue
            val name = childText(w, "name")?.trim().orEmpty()
            val ext = elements(w.getElementsByTagName("gpstel:gate")).firstOrNull()
            val heading = ext?.getAttribute("heading")?.toDoubleOrNull()
            val width = ext?.getAttribute("width")?.toDoubleOrNull()
            val gate = Gate(
                lat, lon,
                heading ?: perpendicular(path, LatLon(lat, lon)),
                (width ?: defaultWidth(tolerance)).coerceIn(4.0, 500.0),
            )
            val role = ext?.getAttribute("role")?.takeIf { it.isNotEmpty() } ?: when (name.lowercase()) {
                in START_NAMES -> "start"
                in FINISH_NAMES -> "finish"
                else -> "split"
            }
            when (role) {
                "start" -> if (start == null) start = gate else splits.add(SplitGate(name, gate))
                "finish" -> finish = gate
                else -> splits.add(SplitGate(name.ifBlank { "Parziale ${splits.size + 1}" }, gate))
            }
        }

        if (start == null && path.size < 2) {
            throw ImportException("Nessun percorso o punto di partenza nel file")
        }

        // Partenza/traguardo dal percorso se mancano i waypoint.
        val first = path.firstOrNull()?.let { LatLon(it.lat, it.lon) }
        val last = path.lastOrNull()?.let { LatLon(it.lat, it.lon) }
        val loop = first != null && last != null && path.size > 2 && Geo.haversine(first, last) <= LOOP_DISTANCE_M
        val type = metaType ?: when {
            finish != null -> TrackType.OPEN
            loop -> TrackType.CIRCULAR
            path.size >= 2 -> TrackType.OPEN
            else -> TrackType.CIRCULAR
        }
        if (start == null) start = Gate(first!!.lat, first.lon, perpendicular(path, first), defaultWidth(tolerance))
        if (type == TrackType.OPEN && finish == null && last != null) {
            finish = Gate(last.lat, last.lon, perpendicular(path, last), defaultWidth(tolerance))
        }
        if (type == TrackType.OPEN && finish == null) {
            throw ImportException("Tracciato aperto senza traguardo")
        }

        val name = childText(firstElement(root, "metadata"), "name")
            ?: childText(firstElement(root, "trk"), "name")
            ?: childText(firstElement(root, "rte"), "name")
        val simplified = if (path.size > MAX_PATH_POINTS) Geo.simplify(path, 1.0) else path

        Track(
            id = newId,
            name = name?.trim()?.takeIf { it.isNotEmpty() }?.take(60) ?: "Tracciato importato",
            type = type,
            tolerance = tolerance,
            createdAt = now,
            start = start,
            finish = if (type == TrackType.OPEN) finish else null,
            splits = splits,
            path = simplified.toMutableList(),
        )
    }

    /* ------------------------------ Utilità ------------------------------ */

    private fun defaultWidth(tolerance: Double) = (2 * tolerance).coerceAtLeast(20.0)

    /** Orientamento perpendicolare al percorso nel punto (0 se non c'è percorso). */
    private fun perpendicular(path: List<PathPoint>, p: LatLon): Double =
        Geo.pathHeadingAt(path, p)?.let { ((it + 90.0) % 180.0 + 180.0) % 180.0 } ?: 0.0

    private fun points(list: NodeList): List<PathPoint> = elements(list).mapNotNull { e ->
        val lat = e.getAttribute("lat").toDoubleOrNull() ?: return@mapNotNull null
        val lon = e.getAttribute("lon").toDoubleOrNull() ?: return@mapNotNull null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return@mapNotNull null
        PathPoint(lat, lon, childText(e, "ele")?.trim()?.toDoubleOrNull())
    }

    private fun elements(list: NodeList): List<Element> =
        (0 until list.length).mapNotNull { list.item(it) as? Element }

    private fun localName(e: Element) = e.tagName.substringAfter(':')

    private fun firstElement(parent: Element, tag: String): Element? =
        (0 until parent.childNodes.length).map { parent.childNodes.item(it) }
            .firstOrNull { it is Element && localName(it) == tag } as? Element

    private fun childText(parent: Element?, tag: String): String? =
        parent?.let { firstElement(it, tag)?.textContent }
}
