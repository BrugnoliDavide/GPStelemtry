package com.gpstelemetry.core

/**
 * Lettore minimo dei file FIT (Garmin, Coros, Wahoo, Suunto…): solo i
 * messaggi "record" (n. 20) con orario, posizione, quota, velocità e
 * frequenza cardiaca. Gestisce intestazioni con orario compresso, campi
 * degli sviluppatori (saltati) e architettura little/big endian.
 */
internal object Fit {
    private const val RECORD = 20
    /** Secondi tra 1970-01-01 e l'epoca FIT (1989-12-31 00:00 UTC). */
    private const val FIT_EPOCH_S = 631_065_600L
    private const val SEMI_TO_DEG = 180.0 / 2147483648.0

    private class Field(val num: Int, val size: Int)
    private class Def(val global: Int, val bigEndian: Boolean, val fields: List<Field>, val devBytes: Int) {
        val size = fields.sumOf { it.size } + devBytes
    }

    fun parse(b: ByteArray): ExternalRecording.Recording {
        val headerSize = b[0].toInt() and 0xFF
        if (headerSize < 12 || b.size < headerSize) throw ExternalRecording.ImportException("File FIT non valido")
        val dataSize = u32(b, 4, false)
        val end = minOf(b.size.toLong(), headerSize + dataSize).toInt()
        val defs = arrayOfNulls<Def>(16)
        val samples = mutableListOf<GpsSample>()
        val hr = mutableListOf<HrSample>()
        var lastTs = -1L // secondi FIT
        var p = headerSize
        try {
            while (p < end) {
                val h = b[p++].toInt() and 0xFF
                if (h and 0x80 != 0) {
                    // Intestazione con orario compresso: è un messaggio di dati.
                    val local = (h shr 5) and 0x03
                    val offset = (h and 0x1F).toLong()
                    if (lastTs >= 0) {
                        lastTs = if (offset >= (lastTs and 0x1F)) (lastTs and 0x1F.inv().toLong()) + offset
                        else (lastTs and 0x1F.inv().toLong()) + offset + 0x20
                    }
                    val d = defs[local] ?: throw ExternalRecording.ImportException("File FIT danneggiato")
                    val v = read(b, p, d)
                    p += d.size
                    if (d.global == RECORD) record(v, lastTs, samples, hr)
                    continue
                }
                val local = h and 0x0F
                if (h and 0x40 != 0) {
                    // Definizione.
                    val big = b[p + 1].toInt() == 1
                    val global = u16(b, p + 2, big)
                    val n = b[p + 4].toInt() and 0xFF
                    p += 5
                    val fields = (0 until n).map { k -> Field(b[p + 3 * k].toInt() and 0xFF, b[p + 3 * k + 1].toInt() and 0xFF) }
                    p += 3 * n
                    var dev = 0
                    if (h and 0x20 != 0) {
                        val nd = b[p++].toInt() and 0xFF
                        for (k in 0 until nd) dev += b[p + 3 * k + 1].toInt() and 0xFF
                        p += 3 * nd
                    }
                    defs[local] = Def(global, big, fields, dev)
                } else {
                    val d = defs[local] ?: throw ExternalRecording.ImportException("File FIT danneggiato")
                    val v = read(b, p, d)
                    p += d.size
                    v[253]?.let { lastTs = it }
                    if (d.global == RECORD) record(v, lastTs, samples, hr)
                }
            }
        } catch (e: IndexOutOfBoundsException) {
            // File troncato: si tiene quanto letto fin qui.
        }
        return ExternalRecording.Recording(samples, hr, "FIT")
    }

    /** Valori dei campi (interi senza segno fino a 4 byte); i campi più lunghi si saltano. */
    private fun read(b: ByteArray, at: Int, d: Def): Map<Int, Long> {
        val out = HashMap<Int, Long>()
        var p = at
        for (f in d.fields) {
            when (f.size) {
                1 -> out[f.num] = (b[p].toInt() and 0xFF).toLong()
                2 -> out[f.num] = u16(b, p, d.bigEndian).toLong()
                4 -> out[f.num] = u32(b, p, d.bigEndian)
            }
            p += f.size
        }
        return out
    }

    private fun record(v: Map<Int, Long>, lastTs: Long, samples: MutableList<GpsSample>, hr: MutableList<HrSample>) {
        val ts = v[253] ?: lastTs
        if (ts < 0) return
        val t = (ts + FIT_EPOCH_S) * 1000
        v[3]?.takeIf { it in 20..250 }?.let { hr += HrSample(t, it.toInt()) }
        val latRaw = v[0] ?: return
        val lonRaw = v[1] ?: return
        if (latRaw == 0x7FFFFFFFL || lonRaw == 0x7FFFFFFFL) return
        val lat = latRaw.toInt() * SEMI_TO_DEG
        val lon = lonRaw.toInt() * SEMI_TO_DEG
        val alt = (v[78]?.takeIf { it != 0xFFFFFFFFL } ?: v[2]?.takeIf { it != 0xFFFFL })?.let { it / 5.0 - 500 }
        val speed = (v[73]?.takeIf { it != 0xFFFFFFFFL } ?: v[6]?.takeIf { it != 0xFFFFL })?.let { (it / 1000.0).toFloat() }
        samples += GpsSample(t = t, lat = lat, lon = lon, ele = alt, speed = speed)
    }

    private fun u16(b: ByteArray, p: Int, big: Boolean): Int {
        val a = b[p].toInt() and 0xFF
        val c = b[p + 1].toInt() and 0xFF
        return if (big) (a shl 8) or c else (c shl 8) or a
    }

    private fun u32(b: ByteArray, p: Int, big: Boolean): Long {
        var v = 0L
        for (k in 0 until 4) {
            val byte = (b[p + k].toLong() and 0xFF)
            v = if (big) (v shl 8) or byte else v or (byte shl (8 * k))
        }
        return v
    }
}
