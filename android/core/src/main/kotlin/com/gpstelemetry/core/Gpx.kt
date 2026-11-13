package com.gpstelemetry.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * Generazione GPX 1.1 — formato generico compatibile con Insta360 Studio.
 * Include quota, orario UTC, velocità/rotta e frequenza cardiaca nelle
 * estensioni Garmin TrackPointExtension.
 */
object Gpx {
    /** Finestra massima (ms) per associare un campione HR a un punto traccia. */
    const val HR_MATCH_WINDOW_MS = 5_000L

    private fun isoFormatter() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private fun esc(s: String) = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun header(name: String, iso: SimpleDateFormat): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<gpx version=\"1.1\" creator=\"GPSTelemetry\"\n")
        append("  xmlns=\"http://www.topografix.com/GPX/1/1\"\n")
        append("  xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n")
        append("  xmlns:gpxtpx=\"http://www.garmin.com/xmlschemas/TrackPointExtension/v2\"\n")
        append("  xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd\">\n")
        append("  <metadata><name>").append(esc(name)).append("</name><time>")
        append(iso.format(Date())).append("</time></metadata>\n")
    }

    private fun wpt(lat: Double, lon: Double, name: String) =
        "  <wpt lat=\"%.7f\" lon=\"%.7f\"><name>%s</name></wpt>\n".format(Locale.US, lat, lon, esc(name))

    /**
     * Cerca il campione HR più vicino nel tempo (entro HR_MATCH_WINDOW_MS).
     * hr deve essere ordinato per timestamp; fromIndex evita ricerche O(n²).
     */
    internal fun nearestHr(hr: List<HrSample>, t: Long, fromIndex: Int): Pair<Int?, Int> {
        var i = fromIndex
        while (i < hr.size - 1 && hr[i + 1].t <= t) i++
        var best: HrSample? = null
        if (i < hr.size) best = hr[i]
        if (i + 1 < hr.size) {
            val next = hr[i + 1]
            if (best == null || abs(next.t - t) < abs(best.t - t)) best = next
        }
        val bpm = best?.takeIf { abs(it.t - t) <= HR_MATCH_WINDOW_MS }?.bpm
        return bpm to i
    }

    private fun trkpt(p: GpsSample, bpm: Int?, iso: SimpleDateFormat): String = buildString {
        append("    <trkpt lat=\"%.7f\" lon=\"%.7f\">".format(Locale.US, p.lat, p.lon))
        p.ele?.let { append("<ele>%.1f</ele>".format(Locale.US, it)) }
        append("<time>").append(iso.format(Date(p.t))).append("</time>")
        val ext = StringBuilder()
        bpm?.let { ext.append("<gpxtpx:hr>").append(it).append("</gpxtpx:hr>") }
        p.speed?.let { ext.append("<gpxtpx:speed>%.2f</gpxtpx:speed>".format(Locale.US, it)) }
        p.heading?.let { ext.append("<gpxtpx:course>%.0f</gpxtpx:course>".format(Locale.US, it)) }
        if (ext.isNotEmpty()) {
            append("<extensions><gpxtpx:TrackPointExtension>")
            append(ext)
            append("</gpxtpx:TrackPointExtension></extensions>")
        }
        append("</trkpt>\n")
    }

    /** GPX di una sessione cronometrata (telemetria GPS completa + HR). */
    fun session(session: Session): String {
        val iso = isoFormatter()
        val hrSorted = session.hr.sortedBy { it.t }
        return buildString {
            append(header(session.name, iso))
            append(wpt(session.trackStart.lat, session.trackStart.lon, "Partenza"))
            if (session.trackType == TrackType.OPEN) {
                session.trackFinish?.let { append(wpt(it.lat, it.lon, "Traguardo")) }
            }
            session.trackSplits.forEachIndexed { i, sp ->
                append(wpt(sp.gate.lat, sp.gate.lon, sp.name.ifBlank { "Parziale ${i + 1}" }))
            }
            append("  <trk><name>").append(esc(session.name)).append("</name><trkseg>\n")
            var hrIdx = 0
            for (p in session.samples) {
                val (bpm, idx) = nearestHr(hrSorted, p.t, hrIdx)
                hrIdx = idx
                append(trkpt(p, bpm, iso))
            }
            append("  </trkseg></trk>\n</gpx>\n")
        }
    }

    /** Namespace delle estensioni GPSTelemetry (ignorate dagli altri programmi). */
    const val GPSTEL_NS = "https://gpstelemetry.app/xmlschemas/track/v1"

    /** Waypoint di una linea di cronometraggio, con orientamento e larghezza. */
    private fun gateWpt(g: Gate, name: String, role: String) =
        ("  <wpt lat=\"%.7f\" lon=\"%.7f\"><name>%s</name><extensions>" +
            "<gpstel:gate role=\"%s\" heading=\"%.2f\" width=\"%.2f\"/></extensions></wpt>\n")
            .format(Locale.US, g.lat, g.lon, esc(name), role, g.headingDeg, g.widthM)

    /**
     * GPX del solo tracciato definito (percorso + linee di cronometraggio).
     * Tipo, tolleranza, orientamento e larghezza delle linee viaggiano in
     * estensioni GPSTelemetry: reimportando il file il tracciato è identico.
     */
    fun track(track: Track): String {
        val iso = isoFormatter()
        return buildString {
            append(
                header(track.name, iso)
                    .replace(
                        "  xmlns:gpxtpx=",
                        "  xmlns:gpstel=\"$GPSTEL_NS\"\n  xmlns:gpxtpx=",
                    )
                    .replace(
                        "</time></metadata>",
                        "</time><extensions><gpstel:track type=\"${track.type.name}\" tolerance=\"%.1f\"/></extensions></metadata>"
                            .format(Locale.US, track.tolerance),
                    )
            )
            append(gateWpt(track.start, "Partenza", "start"))
            if (track.type == TrackType.OPEN) {
                track.finish?.let { append(gateWpt(it, "Traguardo", "finish")) }
            }
            track.splits.forEachIndexed { i, sp ->
                append(gateWpt(sp.gate, sp.name.ifBlank { "Parziale ${i + 1}" }, "split"))
            }
            if (track.path.isNotEmpty()) {
                append("  <trk><name>").append(esc(track.name)).append("</name><trkseg>\n")
                for (p in track.path) {
                    append("    <trkpt lat=\"%.7f\" lon=\"%.7f\">".format(Locale.US, p.lat, p.lon))
                    p.ele?.let { append("<ele>%.1f</ele>".format(Locale.US, it)) }
                    append("</trkpt>\n")
                }
                append("  </trkseg></trk>\n")
            }
            append("</gpx>\n")
        }
    }

    /** CSV dei campioni GPS. */
    fun gpsCsv(session: Session): String {
        val iso = isoFormatter()
        val sb = StringBuilder("time_iso,lat,lon,ele_m,speed_ms,heading_deg,accuracy_m\n")
        for (p in session.samples) {
            sb.append(iso.format(Date(p.t))).append(',')
                .append(p.lat).append(',').append(p.lon).append(',')
                .append(p.ele ?: "").append(',')
                .append(p.speed ?: "").append(',')
                .append(p.heading ?: "").append(',')
                .append(p.acc ?: "").append('\n')
        }
        return sb.toString()
    }

    /** CSV della telemetria di moto (accelerometro + inclinazione). */
    fun motionCsv(session: Session): String {
        val iso = isoFormatter()
        val sb = StringBuilder("time_iso,ax_ms2,ay_ms2,az_ms2,pitch_deg,roll_deg\n")
        for (m in session.motion) {
            sb.append(iso.format(Date(m.t))).append(',')
                .append(m.ax).append(',').append(m.ay).append(',').append(m.az).append(',')
                .append(m.pitch ?: "").append(',').append(m.roll ?: "").append('\n')
        }
        return sb.toString()
    }

    /** CSV della frequenza cardiaca. */
    fun hrCsv(session: Session): String {
        val iso = isoFormatter()
        val sb = StringBuilder("time_iso,bpm\n")
        for (h in session.hr.sortedBy { it.t }) {
            sb.append(iso.format(Date(h.t))).append(',').append(h.bpm).append('\n')
        }
        return sb.toString()
    }
}
