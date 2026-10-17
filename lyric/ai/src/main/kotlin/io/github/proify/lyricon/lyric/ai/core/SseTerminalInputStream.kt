package io.github.proify.lyricon.lyric.ai.core

import java.io.InputStream

/** Stop at the protocol terminator instead of draining a proxy's possibly broken HTTP tail. */
internal class SseTerminalInputStream(private val source: InputStream) : InputStream() {
    private val line = StringBuilder(64)
    private var longLine = false
    private var dataLines = 0
    private var doneData = false
    private var ended = false

    override fun read(): Int {
        if (ended) return -1
        return source.read().also { if (it >= 0) accept(it) }
    }

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        if (offset < 0 || length < 0 || offset > bytes.size - length) {
            throw IndexOutOfBoundsException()
        }
        if (length == 0) return 0
        if (ended) return -1
        val count = source.read(bytes, offset, length)
        for (i in 0 until count) {
            accept(bytes[offset + i].toInt() and 0xff)
            if (ended) return i + 1
        }
        return count
    }

    private fun accept(value: Int) {
        if (value != '\n'.code) {
            if (line.length < 64) line.append(value.toChar()) else longLine = true
            return
        }

        val text = line.toString().removeSuffix("\r")
        if (text.isEmpty() && !longLine) {
            ended = dataLines == 1 && doneData
            dataLines = 0
            doneData = false
        } else if (text == "data" || text.startsWith("data:")) {
            dataLines++
            doneData = !longLine && text.substringAfter(':', "").trim() == "[DONE]"
        }
        line.setLength(0)
        longLine = false
    }

    override fun close() = source.close()
}
