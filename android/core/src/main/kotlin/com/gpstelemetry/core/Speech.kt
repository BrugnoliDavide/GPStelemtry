package com.gpstelemetry.core

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Frasi lette dalla voce al pilota (in italiano), brevi e sempre nello
 * stesso ordine perché si capiscano al primo ascolto senza guardare il
 * telefono:
 *
 *   "Settore 3, Polledrara, completato in 1 minuto e 13 secondi.
 *    5 secondi meglio del record."
 *   "Settore 2 completato in 41,3 secondi. Settore viola! 4 decimi meglio del record."
 *   "Giro completato in 1 minuto e 45 secondi. 2 secondi più lento del record."
 *
 * I numeri sono scritti in cifre con la virgola decimale: il sintetizzatore
 * italiano li legge "tredici virgola quattro".
 */
object Speech {

    /** Tempo parlato, arrotondato al decimo: "1 minuto e 13,4 secondi". */
    fun time(ms: Long): String {
        val tenths = (ms / 100.0).roundToLong().coerceAtLeast(0)
        val minutes = tenths / 600
        val secTenths = tenths % 600
        val sec = seconds(secTenths)
        return when {
            minutes == 0L -> sec
            secTenths == 0L -> minutes(minutes)
            else -> "${minutes(minutes)} e $sec"
        }
    }

    /** Distacco parlato: "4 decimi", "1 secondo", "2,3 secondi". */
    fun gap(ms: Long): String {
        val tenths = (abs(ms) / 100.0).roundToLong()
        return when {
            tenths < 10 -> if (tenths == 1L) "1 decimo" else "$tenths decimi"
            else -> seconds(tenths)
        }
    }

    private fun minutes(m: Long) = if (m == 1L) "1 minuto" else "$m minuti"

    private fun seconds(tenths: Long): String {
        val s = tenths / 10
        val d = tenths % 10
        return when {
            d != 0L -> "$s,$d secondi"
            s == 1L -> "1 secondo"
            else -> "$s secondi"
        }
    }

    /** Confronto con il record: "Settore viola! 4 decimi meglio del record." */
    private fun comparison(timeMs: Long, ref: Sectors.Stat?, purpleWord: String): String {
        if (ref == null) return "Primo tempo."
        val delta = timeMs - ref.best
        val tenths = (abs(delta) / 100.0).roundToLong()
        return when {
            tenths == 0L -> "Pari al record."
            delta < 0 -> "$purpleWord ${gap(delta)} meglio del record."
            else -> "${gap(delta)} più lento del record."
        }
    }

    /**
     * Settore concluso. [number] è il numero del settore cronometrato
     * (le neutralizzazioni non contano), [name] il nome personalizzato del
     * parziale che lo chiude (null se generico).
     */
    fun sector(number: Int, name: String?, timeMs: Long, ref: Sectors.Stat?): String {
        val head = if (name.isNullOrBlank()) "Settore $number" else "Settore $number, ${name.trim()},"
        return "$head completato in ${time(timeMs)}. ${comparison(timeMs, ref, "Settore viola!")}"
    }

    /** Giro concluso. */
    fun lap(timeMs: Long, ref: Sectors.Stat?): String =
        "Giro completato in ${time(timeMs)}. ${comparison(timeMs, ref, "Nuovo record!")}"

    const val START = "Via, tempo partito."
    const val NEUTRAL_ENTER = "Neutralizzazione, tempo fermo."
    const val NEUTRAL_EXIT = "Fine neutralizzazione, tempo ripartito."

    /** Esempio per la prova della voce nelle impostazioni. */
    val SAMPLE: String get() = sector(3, "Polledrara", 73_000, Sectors.Stat(78_000, 80_000, 4))

    /**
     * Nome da leggere per il settore [sectorIndex] del tracciato: quello del
     * parziale che lo chiude, se è stato personalizzato (non "Parziale N").
     */
    fun sectorName(splits: List<SplitGate>, sectorIndex: Int): String? =
        splits.getOrNull(sectorIndex)?.name?.trim()?.takeIf {
            it.isNotEmpty() && !it.startsWith("Parziale") && !it.startsWith("Neutralizzazione") &&
                !it.startsWith("Fine neutralizzazione")
        }

    /** Numero (da 1) del settore cronometrato [sectorIndex], escluse le neutralizzazioni. */
    fun sectorNumber(splits: List<SplitGate>, sectorIndex: Int): Int =
        Neutral.sectorMask(splits).take(sectorIndex + 1).count { !it }
}
