package io.github.proify.lyricon.lyric.ai.core

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class AiConversationTest {
    @Test fun streamingFollowUpSerializesRealUserAndAssistantMessagesInOrder() = runBlocking {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val body = CompletableFuture<String>()
            thread(isDaemon = true) {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val input = socket.getInputStream()
                        val header = StringBuilder()
                        while (!header.endsWith("\r\n\r\n")) {
                            val next = input.read()
                            check(next >= 0)
                            header.append(next.toChar())
                        }
                        val length = Regex("Content-Length: (\\d+)", RegexOption.IGNORE_CASE)
                            .find(header)!!.groupValues[1].toInt()
                        val bytes = ByteArray(length)
                        var count = 0
                        while (count < length) {
                            val read = input.read(bytes, count, length - count)
                            check(read > 0)
                            count += read
                        }
                        body.complete(bytes.toString(Charsets.UTF_8))
                        val data = "data: {\"id\":\"test\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"test\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"回答\"}}]}\n\ndata: [DONE]\n\n".toByteArray()
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: ${data.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(data)
                            flush()
                        }
                    }
                } catch (failure: Throwable) { body.completeExceptionally(failure) }
            }
            val result = withTimeout(6000) {
                AiChatClient.stream(AiConfig("local-only", "test", "http://127.0.0.1:${server.localPort}/v1"),
                    "system", "歌曲与歌词", conversation = listOf(
                        AiChatMessage("首次解读", assistant = true), AiChatMessage("这句有什么隐喻？")))
            }
            assertEquals("回答", result?.content)
            val messages = Json.parseToJsonElement(body.get(1, TimeUnit.SECONDS)).jsonObject["messages"]!!.jsonArray
            assertEquals(listOf("system", "user", "assistant", "user"), messages.map { it.jsonObject["role"]!!.jsonPrimitive.content })
            assertEquals(listOf("system", "歌曲与歌词", "首次解读", "这句有什么隐喻？"),
                messages.map { it.jsonObject["content"]!!.jsonPrimitive.content })
        }
    }
}
