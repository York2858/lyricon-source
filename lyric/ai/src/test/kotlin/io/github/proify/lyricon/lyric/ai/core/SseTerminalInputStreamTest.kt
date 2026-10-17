package io.github.proify.lyricon.lyric.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream

class SseTerminalInputStreamTest {
    @Test fun stopsBeforeReadingBrokenTailEvenWhenEventsArriveByteByByte() {
        val event = "data: {\"content\":\"你好\"}\r\n\r\ndata: [DONE]\r\n\r\n"
        val bytes = event.toByteArray()
        var index = 0
        var closed = false
        val source = object : InputStream() {
            override fun read(): Int {
                check(index < bytes.size) { "Read beyond completed SSE response" }
                return bytes[index++].toInt() and 0xff
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                b[off] = read().toByte()
                return 1
            }
            override fun close() { closed = true }
        }
        SseTerminalInputStream(source).use {
            assertEquals(event, it.readBytes().toString(Charsets.UTF_8))
            assertEquals(-1, it.read())
            assertFalse(closed)
        }
        assertTrue(closed)
    }

    @Test fun ignoresDoneInsideJsonCommentsOrMultilineData() {
        val prefix = ":data: [DONE]\n\ndata: {\"content\":\"[DONE]\"}\n\n" +
            "data: another line\ndata: [DONE]\n\n"
        val input = prefix + "data:[DONE]\n\nignored transport tail"
        val stream = SseTerminalInputStream(input.byteInputStream())
        assertEquals(prefix + "data:[DONE]\n\n", stream.readBytes().toString(Charsets.UTF_8))
    }

    @Test fun doesNotHideFailureBeforeProtocolTerminator() {
        val source = object : InputStream() {
            override fun read(): Int = throw java.io.IOException("connection reset")
        }
        try {
            SseTerminalInputStream(source).read()
            org.junit.Assert.fail("Expected original transport failure")
        } catch (e: java.io.IOException) {
            assertEquals("connection reset", e.message)
        }
    }
}
