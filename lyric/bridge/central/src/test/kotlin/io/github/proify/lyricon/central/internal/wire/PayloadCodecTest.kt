package io.github.proify.lyricon.central.internal.wire

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream
import java.util.zip.DataFormatException

class PayloadCodecTest {
    private fun compress(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        DeflaterOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }
    @Test fun standardZlibPayloadRoundTrips() {
        val bytes = "中文 Lyrics 🌍".repeat(2000).toByteArray()
        assertArrayEquals(bytes, compress(bytes).inflate())
    }
    @Test(expected = DataFormatException::class) fun truncatedStreamIsRejected() {
        val bytes = compress("lyrics".repeat(1000).toByteArray())
        bytes.copyOf(bytes.size - 3).inflate()
    }
    @Test(expected = DataFormatException::class, timeout = 2000) fun dictionaryStreamCannotSpin() {
        byteArrayOf(0x78, 0x20, 0, 0, 0, 1).inflate()
    }
    @Test fun emptyPayloadIsPreserved() { assertArrayEquals(byteArrayOf(), byteArrayOf().inflate()) }
}
