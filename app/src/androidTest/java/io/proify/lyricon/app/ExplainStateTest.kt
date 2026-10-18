package io.github.proify.lyricon.app.activity.lyric

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import io.github.proify.lyricon.app.ai.explain.*
import io.github.proify.lyricon.lyric.ai.core.AiConfig
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Exercises real SSE transport on the device, without external services or credentials. */
class ExplainStateTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as Application
    private var state: ExplainViewModel? = null
    private val song = ExplainSong("本地测试-${System.nanoTime()}", "测试歌手", "测试专辑",
        listOf(ExplainLine("相同的一句", 0, 1000), ExplainLine("相同的一句", 2000, 3000)))

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
    @After fun clear() { onMain { state?.stop(); state?.viewModelScope?.cancel() } }
    private suspend fun await(predicate: (ExplainViewModel) -> Boolean) {
        withTimeout(8000) {
            while (true) {
                var ready = false
                onMain { ready = state?.let(predicate) == true }
                if (ready) return@withTimeout
                delay(30)
            }
        }
    }

    @Test fun stoppingSsePreservesTextAndDisconnectsTheSocket() = runBlocking {
        LocalServer { socket, _ ->
            val bytes = event("已经收到的内容").toByteArray()
            socket.getOutputStream().apply {
                write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: 999999\r\n\r\n".toByteArray())
                write(bytes); flush()
            }
            socket.getInputStream().read()
        }.use { server ->
            onMain { state = ExplainViewModel(app, song, 500, null) { server.config } }
            await { it.record.turns.lastOrNull()?.content == "已经收到的内容" }
            onMain { state!!.stop() }
            await { !it.isLoading }
            server.finished.get(3, TimeUnit.SECONDS)
            onMain {
                assertEquals("已经收到的内容", state!!.record.turns.last().content)
                assertEquals(ExplainStatus.STOPPED, state!!.record.turns.last().status)
            }
        }
    }

    @Test fun followUpForksFavoriteAndSendsOriginalContext() = runBlocking {
        LocalServer { socket, index -> socket.getOutputStream().apply {
            val body = (event(if (index == 0) "首次解读" else "结合上文的追问回答") + "data: [DONE]\n\n").toByteArray()
            write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(body); flush()
        } }.use { server ->
            onMain { state = ExplainViewModel(app, song, 2500, null) { server.config } }
            await { !it.isLoading && it.record.turns.firstOrNull()?.status == ExplainStatus.COMPLETE }
            var originalId = ""
            onMain { originalId = state!!.record.id; assertEquals(1, state!!.record.lineIndex); state!!.toggleFavorite() }
            await { it.records.any { record -> record.id == originalId && record.favorite } }
            onMain { state!!.ask("这里有什么隐喻？") }
            await { !it.isLoading && it.record.turns.size == 2 }
            onMain {
                assertNotEquals(originalId, state!!.record.id)
                assertFalse(state!!.record.favorite)
                assertTrue(state!!.records.first { it.id == originalId }.favorite)
                assertEquals(1, state!!.records.first { it.id == originalId }.turns.size)
                assertEquals("结合上文的追问回答", state!!.record.turns.last().content)
            }
            server.requests.poll(3, TimeUnit.SECONDS)
            val request = server.requests.poll(3, TimeUnit.SECONDS)!!
            val messages = Json.parseToJsonElement(request).jsonObject["messages"]!!.jsonArray
            assertEquals(listOf("system", "user", "assistant", "user"), messages.map { it.jsonObject["role"]!!.jsonPrimitive.content })
            assertTrue(messages[1].jsonObject["content"]!!.jsonPrimitive.content.contains("指定第 2 行"))
            assertEquals("首次解读", messages[2].jsonObject["content"]!!.jsonPrimitive.content)
        }
    }

    @Test fun brokenStreamKeepsPartialAnswerAndDoesNotBecomeACompletedResult() = runBlocking {
        LocalServer { socket, _ -> socket.getOutputStream().apply {
            write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: 999999\r\nConnection: close\r\n\r\n".toByteArray())
            write(event("保留这段不完整的文字").toByteArray()); flush()
        } }.use { server ->
            onMain { state = ExplainViewModel(app, song, 500, null) { server.config } }
            await { !it.isLoading && it.record.turns.isNotEmpty() }
            onMain {
                assertEquals(ExplainStatus.ERROR, state!!.record.turns.last().status)
                assertEquals("保留这段不完整的文字", state!!.record.turns.last().content)
                assertNotNull(state!!.error)
            }
            await { it.records.any { record -> record.id == it.record.id && record.turns.last().status == ExplainStatus.ERROR } }
        }
    }

    private class LocalServer(private val respond: (Socket, Int) -> Unit) : AutoCloseable {
        private val socket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val config = AiConfig("local-test-only", "test-model", "http://127.0.0.1:${socket.localPort}/v1")
        val requests = LinkedBlockingQueue<String>()
        val finished = CompletableFuture<Unit>()
        private val worker = thread(isDaemon = true) {
            try {
                var index = 0
                while (!socket.isClosed) socket.accept().use { peer ->
                    peer.soTimeout = 5000
                    val input = peer.getInputStream()
                    val headers = StringBuilder()
                    while (!headers.endsWith("\r\n\r\n")) {
                        val next = input.read(); check(next >= 0); headers.append(next.toChar())
                    }
                    val length = Regex("Content-Length: (\\d+)", RegexOption.IGNORE_CASE)
                        .find(headers)!!.groupValues[1].toInt()
                    val bytes = ByteArray(length)
                    var read = 0
                    while (read < length) { val count = input.read(bytes, read, length - read); check(count > 0); read += count }
                    requests.put(bytes.toString(Charsets.UTF_8))
                    respond(peer, index++)
                    finished.complete(Unit)
                }
            } catch (_: Exception) { }
        }
        override fun close() { socket.close(); worker.join(500) }
    }

    companion object {
        private fun event(content: String): String = "data: " + buildJsonObject {
            put("id", "test"); put("object", "chat.completion.chunk"); put("created", 1); put("model", "test-model")
            putJsonArray("choices") { addJsonObject { put("index", 0); putJsonObject("delta") { put("content", content) } } }
        }.toString() + "\n\n"
    }
}
