package com.gpstelemetry.app

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files

/** Estrazione del pacchetto della voce Piper (.tar.bz2). */
class PiperModelTest {

    private fun archive(vararg files: Pair<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        TarArchiveOutputStream(BZip2CompressorOutputStream(bytes)).use { tar ->
            for ((name, content) in files) {
                val data = content.toByteArray()
                tar.putArchiveEntry(TarArchiveEntry(name).apply { size = data.size.toLong() })
                tar.write(data)
                tar.closeArchiveEntry()
            }
        }
        return bytes.toByteArray()
    }

    @Test
    fun estraeSoloQuelloCheServe() {
        val n = PiperModel.NAME
        val dir = Files.createTempDirectory("piper").toFile()
        PiperModel.extract(
            ByteArrayInputStream(
                archive(
                    "$n/it_IT-paola-medium.onnx" to "modello",
                    "$n/tokens.txt" to "a 1",
                    "$n/espeak-ng-data/phontab" to "x",
                    "$n/espeak-ng-data/it_dict" to "it",
                    "$n/espeak-ng-data/ru_dict" to "ru",
                    "$n/espeak-ng-data/lang/roa/it" to "voce",
                )
            ),
            dir,
        )
        assertEquals("modello", dir.resolve("it_IT-paola-medium.onnx").readText())
        assertTrue(dir.resolve("espeak-ng-data/it_dict").exists())
        assertTrue(dir.resolve("espeak-ng-data/lang/roa/it").exists())
        assertFalse("dizionari di altre lingue esclusi", dir.resolve("espeak-ng-data/ru_dict").exists())
        dir.deleteRecursively()
    }

    @Test(expected = IllegalArgumentException::class)
    fun rifiutaPercorsiFuoriDallaCartella() {
        val dir = Files.createTempDirectory("piper").toFile()
        try {
            PiperModel.extract(ByteArrayInputStream(archive("${PiperModel.NAME}/../../evil.txt" to "x")), dir)
        } finally {
            dir.deleteRecursively()
        }
    }
}
