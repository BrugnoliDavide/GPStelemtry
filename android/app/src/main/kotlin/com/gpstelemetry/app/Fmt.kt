package com.gpstelemetry.app

import java.util.Locale

/** Formattazione di tempi e distanze per l'interfaccia. */
object Fmt {
    fun time(ms: Long?): String {
        if (ms == null) return "—"
        val cs = (ms / 100) % 10
        val s = (ms / 1000) % 60
        val m = (ms / 60000) % 60
        val h = ms / 3600000
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d.%d", h, m, s, cs)
        } else {
            String.format(Locale.US, "%02d:%02d.%d", m, s, cs)
        }
    }

    /** Distanza nel sistema di unità scelto (vedi [Units]). */
    fun dist(m: Double): String = Units.dist(m)
}
