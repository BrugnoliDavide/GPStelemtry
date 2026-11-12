package com.gpstelemetry.app

import java.util.Locale

/** Sistema di unità di misura scelto nelle impostazioni. */
enum class UnitSystem { METRIC, IMPERIAL }

/**
 * Formattazione di velocità, distanze e quote nel sistema scelto
 * (metrico: km/h, m, km — imperiale: mph, ft, mi).
 *
 * I dati restano sempre salvati in unità metriche: la conversione avviene
 * solo nella visualizzazione. Il sistema attivo è caricato all'avvio
 * dell'app ([Settings.applyUnits]) e aggiornato quando cambia.
 */
object Units {
    private const val M_PER_FT = 0.3048
    private const val M_PER_MI = 1609.344
    private const val KMH_PER_MPH = 1.609344

    @Volatile
    var system: UnitSystem = UnitSystem.METRIC

    private fun f(fmt: String, v: Double) = String.format(Locale.US, fmt, v)

    /* ------------------------------ Velocità ----------------------------- */

    fun speedValue(kmh: Double): Double = if (system == UnitSystem.METRIC) kmh else kmh / KMH_PER_MPH
    val speedUnit: String get() = if (system == UnitSystem.METRIC) "km/h" else "mph"

    /** "173 km/h" / "107 mph". */
    fun speed(kmh: Double): String = f("%.0f ", speedValue(kmh)) + speedUnit

    /** Velocità da m/s (come la fornisce il GPS). */
    fun speedMs(ms: Double): String = speed(ms * 3.6)

    /* ------------------------------ Distanze ----------------------------- */

    /** "850 m" / "2.08 km" — "920 ft" / "1.29 mi". */
    fun dist(m: Double): String = when (system) {
        UnitSystem.METRIC -> if (m >= 1000) f("%.2f km", m / 1000) else "${m.toInt()} m"
        UnitSystem.IMPERIAL -> {
            val mi = m / M_PER_MI
            if (mi >= 0.1) f("%.2f mi", mi) else "${(m / M_PER_FT).toInt()} ft"
        }
    }

    /* -------------------------- Quote e lunghezze ------------------------ */

    fun lengthValue(m: Double): Double = if (system == UnitSystem.METRIC) m else m / M_PER_FT
    val lengthUnit: String get() = if (system == UnitSystem.METRIC) "m" else "ft"

    /** Quota o lunghezza breve: "212 m" / "696 ft". */
    fun length(m: Double): String = f("%.0f ", lengthValue(m)) + lengthUnit

    /** Dislivello: "+32 / −12 m" / "+105 / −39 ft". */
    fun elevation(gain: Double, loss: Double): String =
        f("+%.0f", lengthValue(gain)) + f(" / −%.0f ", lengthValue(loss)) + lengthUnit
}
