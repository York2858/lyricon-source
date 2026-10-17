package io.github.proify.lyricon.provider.internal.wire

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.util.zip.InflaterInputStream
import java.util.Random

class PayloadCodecTest {
    @Test fun remainsCompatibleWithStandardZlib() {
        val payloads = listOf("你好，世界 🌍".repeat(1000).toByteArray(),
            ByteArray(32000).also { Random(7).nextBytes(it) })
        payloads.forEach { bytes ->
            assertArrayEquals(bytes, InflaterInputStream(bytes.deflate().inputStream()).use { it.readBytes() })
        }
    }
    @Test fun emptyPayloadIsPreserved() { assertArrayEquals(byteArrayOf(), byteArrayOf().deflate()) }
}
