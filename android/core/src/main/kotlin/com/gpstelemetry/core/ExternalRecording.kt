package com.gpstelemetry.core

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs

/**
 * REGISTRAZIONI DA ALTRI DISPOSITIVI (Insta360, smartwatch, ciclocomputer…):
 * si legge il file GPS, si verifica che sia coerente e girata sul tracciato,
 * e si ricavano i giri come se fossero stati cronometrati dall'app.
 *
 * Formati: GPX (Insta360 Studio, Garmin, Apple Watch tramite app, Strava…),
 * TCX (Garmin, Polar, Suunto), FIT (file nativo di Garmin, Coros, Wahoo…)
 * e NMEA (action cam, data logger). Servono gli ORARI dei punti.
 */
object ExternalRecording {

    /** Quota minima della registrazione dentro il corridoio del tracciato. */
    const val MIN_INSIDE = 0.70
    const val MIN_POINTS = 20
    /** Campionamento massimo accettato (mediana tra due punti). */
    const val MAX_MEDIAN_INTERVAL_MS = 5_000L
    /** Oltre questa velocità tra due punti c'è un salto impossibile (≈430 km/h). */
    const val MAX_SPEED_MS = 120.0
    /** Quota massima di salti impossibili. */
    const val MAX_JUMPS = 0.05

    class ImportException(message: String) : Exception(message)

    data class Recording(
        val samples: List<GpsSample>,
        val hr: List<HrSample>,
        /** Formato riconosciuto (GPX, TCX, FIT, NMEA). */
        val format: String,
        /** Dispositivo/programma che l'ha prodotta, se dichiarato nel file. */
        val creator: String? = null,
    )

    /** Un giro ricavato: risultato come quello del cronometro e campioni del giro. */
    data class Lap(val result: SessionResult, val samples: List<GpsSample>, val hr: List<HrSample>)

    data class Analysis(
        val recording: Recording,
        /** Quota dei punti dentro il corridoio del tracciato (0..1). */
        val inside: Double,
        val laps: List<Lap>,
    ) {
        val best: Lap? get() = laps.minByOrNull { it.result.elapsedMs ?: Long.MAX_VALUE }
    }

    /* ------------------------------ Lettura ------------------------------ */

    fun parse(bytes: ByteArray): Recording {
        if (bytes.size >= 12 && String(bytes, 8, 4, Charsets.US_ASCII) == ".FIT") return Fit.parse(bytes)
        val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.UTF_8)
        return when {
            head.contains("<gpx", ignoreCase = true) -> parseGpx(bytes)
            head.contains("TrainingCenterDatabase") -> parseTcx(bytes)
            Regex("\\$(GP|GN|GL|GA)(RMC|GGA)").containsMatchIn(head) -> parseNmea(String(bytes, Charsets.US_ASCII))
            else -> throw ImportException("Formato non riconosciuto: usa un file GPX, TCX, FIT o NMEA")
        }
    }

    private fun xml(bytes: ByteArray): Element {
        val doc = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            // Nessuna entità esterna o DTD (file da fonti non fidate).
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { isExpandEntityReferences = false }
        }.newDocumentBuilder().let { b ->
            runCatching { b.parse(ByteArrayInputStream(bytes)) }.getOrElse { throw ImportException("File non leggibile (XML non valido)") }
        }
        return doc.documentElement ?: throw ImportException("File vuoto")
    }

    private fun Element.all(local: String): List<Element> {
        val nl = getElementsByTagName("*")
        return (0 until nl.length).map { nl.item(it) as Element }.filter { it.tagName.substringAfter(':') == local }
    }

    private fun Element.child(local: String): Element? {
        val nl = childNodes
        for (i in 0 until nl.length) {
            val n = nl.item(i)
            if (n is Element && n.tagName.substringAfter(':') == local) return n
        }
        return null
    }

    private fun Element.text(local: String) = child(local)?.textContent?.trim()

    /** Orario ISO 8601 (con o senza fuso; senza fuso = UTC). */
    internal fun time(s: String?): Long? {
        val v = s?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { Instant.parse(v).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(v).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(v.removeSuffix("Z")).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
    }

    private fun parseGpx(bytes: ByteArray): Recording {
        val root = xml(bytes)
        val pts = root.all("trkpt").ifEmpty { root.all("rtept") }
        val samples = mutableListOf<GpsSample>()
        val hr = mutableListOf<HrSample>()
        for (p in pts) {
            val lat = p.getAttribute("lat").toDoubleOrNull() ?: continue
            val lon = p.getAttribute("lon").toDoubleOrNull() ?: continue
            val t = time(p.text("time")) ?: continue
            val ele = p.text("ele")?.toDoubleOrNull()
            // Velocità: <speed> (GPX 1.0 / Insta360) o estensioni Garmin.
            val speed = (p.text("speed") ?: p.all("speed").firstOrNull()?.textContent)?.trim()?.toFloatOrNull()
            samples += GpsSample(t = t, lat = lat, lon = lon, ele = ele, speed = speed)
            p.all("hr").firstOrNull()?.textContent?.trim()?.toIntOrNull()?.takeIf { it in 20..250 }?.let { hr += HrSample(t, it) }
        }
        if (pts.isNotEmpty() && samples.isEmpty()) throw ImportException("La registrazione non ha gli orari dei punti: senza non si può cronometrare")
        return Recording(samples, hr, "GPX", root.getAttribute("creator").takeIf { it.isNotBlank() })
    }

    private fun parseTcx(bytes: ByteArray): Recording {
        val root = xml(bytes)
        val samples = mutableListOf<GpsSample>()
        val hr = mutableListOf<HrSample>()
        for (p in root.all("Trackpoint")) {
            val t = time(p.text("Time")) ?: continue
            p.child("HeartRateBpm")?.text("Value")?.toIntOrNull()?.takeIf { it in 20..250 }?.let { hr += HrSample(t, it) }
            val pos = p.child("Position") ?: continue
            val lat = pos.text("LatitudeDegrees")?.toDoubleOrNull() ?: continue
            val lon = pos.text("LongitudeDegrees")?.toDoubleOrNull() ?: continue
            samples += GpsSample(t = t, lat = lat, lon = lon, ele = p.text("AltitudeMeters")?.toDoubleOrNull())
        }
        val creator = root.all("Creator").firstOrNull()?.text("Name")
        return Recording(samples, hr, "TCX", creator)
    }

    /** NMEA: frasi RMC (data, ora, posizione, velocità); GGA aggiunge la quota. */
    private fun parseNmea(text: String): Recording {
        val samples = mutableListOf<GpsSample>()
        var ele: Double? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim().substringBefore('*')
            val f = line.split(',')
            if (f.size < 10) continue
            when (f[0].takeLast(3)) {
                "GGA" -> ele = f.getOrNull(9)?.toDoubleOrNull()
                "RMC" -> {
                    if (f[2] != "A") continue
                    val lat = nmeaDeg(f[3], f[4]) ?: continue
                    val lon = nmeaDeg(f[5], f[6]) ?: continue
                    val t = nmeaTime(f[9], f[1]) ?: continue
                    val speed = f[7].toDoubleOrNull()?.let { (it * 0.514444).toFloat() }
                    samples += GpsSample(t = t, lat = lat, lon = lon, ele = ele, speed = speed)
                }
            }
        }
        return Recording(samples, emptyList(), "NMEA")
    }

    private fun nmeaDeg(v: String, hemi: String): Double? {
        val x = v.toDoubleOrNull() ?: return null
        val deg = (x / 100).toInt()
        val d = deg + (x - deg * 100) / 60.0
        return if (hemi == "S" || hemi == "W") -d else d
    }

    private fun nmeaTime(date: String, time: String): Long? {
        if (date.length != 6 || time.length < 6) return null
        return runCatching {
            LocalDateTime.of(
                2000 + date.substring(4, 6).toInt(), date.substring(2, 4).toInt(), date.substring(0, 2).toInt(),
                time.substring(0, 2).toInt(), time.substring(2, 4).toInt(), time.substring(4, 6).toInt(),
                ((time.substring(6).toDoubleOrNull() ?: 0.0) * 1e9).toInt(),
            ).toInstant(ZoneOffset.UTC).toEpochMilli()
        }.getOrNull()
    }

    /* --------------------------- Verifica e giri -------------------------- */

    /**
     * Pulisce la registrazione (ordine per orario, niente doppioni), verifica
     * che sia coerente e girata sul tracciato per almeno il 70%, poi la passa
     * al cronometro: ogni giro completo diventa un risultato.
     * Lancia [ImportException] con il motivo, in italiano, se non va bene.
     */
    fun analyze(track: Track, rec: Recording): Analysis {
        if (track.path.size < 2) {
            throw ImportException("Il tracciato non ha un percorso disegnato: non si può verificare che la registrazione sia stata fatta qui")
        }
        val s = rec.samples.sortedBy { it.t }.distinctBy { it.t }
        if (s.size < MIN_POINTS) throw ImportException("Registrazione troppo corta: ${s.size} punti GPS (ne servono almeno $MIN_POINTS)")

        // Coerenza: campionamento abbastanza fitto e niente salti impossibili.
        val dts = s.zipWithNext { a, b -> b.t - a.t }.sorted()
        val median = dts[dts.size / 2]
        if (median > MAX_MEDIAN_INTERVAL_MS) {
            throw ImportException("Punti GPS troppo radi (uno ogni ${median / 1000} s): per cronometrare serve almeno un punto ogni ${MAX_MEDIAN_INTERVAL_MS / 1000} s")
        }
        val jumps = s.zipWithNext().count { (a, b) ->
            val dt = (b.t - a.t) / 1000.0
            dt > 0 && Geo.haversine(a.lat, a.lon, b.lat, b.lon) / dt > MAX_SPEED_MS
        }
        if (jumps > s.size * MAX_JUMPS) {
            throw ImportException("Registrazione incoerente: $jumps salti di posizione impossibili. Il segnale GPS del dispositivo non è affidabile")
        }

        // Dentro il tracciato per almeno il 70%.
        val tol = maxOf(5.0, track.tolerance)
        val inside = s.count { Geo.distToPath(LatLon(it.lat, it.lon), track.path) <= tol }.toDouble() / s.size
        if (inside < MIN_INSIDE) {
            val near = s.minOf { Geo.distToPath(LatLon(it.lat, it.lon), track.path) }
            throw ImportException(
                if (near > 1000) "La registrazione non è stata fatta su questo tracciato (il punto più vicino è a ${Fmt.km(near)})"
                else "Solo il ${(inside * 100).toInt()}% della registrazione è dentro il tracciato (serve almeno il ${(MIN_INSIDE * 100).toInt()}%)"
            )
        }

        val laps = laps(track, s, rec.hr.sortedBy { it.t })
        if (laps.isEmpty()) {
            throw ImportException("Nessun giro completo: la registrazione non attraversa la linea di partenza e poi quella del traguardo")
        }
        return Analysis(rec.copy(samples = s), inside, laps)
    }

    /** Giri completi: il cronometro riparte dalla linea appena attraversata. */
    internal fun laps(track: Track, s: List<GpsSample>, hr: List<HrSample>): List<Lap> {
        val out = mutableListOf<Lap>()
        var i = 0
        while (i < s.size - 1) {
            val eng = TimingEngine(track)
            var end = -1
            for (j in i until s.size) {
                eng.feed(s[j])
                if (eng.state == TimingEngine.State.FINISHED) { end = j; break }
            }
            if (end < 0) break
            val start = eng.startTime!!
            val finish = eng.finishTime!!
            val result = eng.summary(finish)
            // Campioni del giro: dal punto prima della partenza al punto dopo il traguardo.
            val from = (s.indexOfFirst { it.t >= start } - 1).coerceAtLeast(i)
            out += Lap(result, s.subList(from, end + 1).toList(), hr.filter { it.t in start..finish })
            // Circuito: il traguardo è anche la partenza del giro dopo (si rilegge il passaggio).
            i = if (track.type == TrackType.CIRCULAR) end - 1 else end + 1
            if (i <= from) i = end
        }
        return out
    }

    /** Già importata? (stesso istante di partenza di un giro salvato, entro 1 s). */
    fun isDuplicate(lap: Lap, existingStarts: Collection<Long>): Boolean =
        lap.result.startTime?.let { st -> existingStarts.any { abs(it - st) < 1000 } } ?: false

    private object Fmt {
        fun km(m: Double) = if (m >= 10_000) "${(m / 1000).toInt()} km" else String.format(java.util.Locale.ITALY, "%.1f km", m / 1000)
    }
}
