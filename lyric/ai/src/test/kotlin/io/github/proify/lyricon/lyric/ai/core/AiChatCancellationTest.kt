package io.github.proify.lyricon.lyric.ai.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Local HTTP only; no API key or paid service is used. */
class AiChatCancellationTest {
    private fun cancellationClosesSocket(sendPartialBody: Boolean, streaming: Boolean = false) = runBlocking {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val arrived = CompletableFuture<Unit>()
            val disconnected = CompletableFuture<Boolean>()
            val peer = thread(isDaemon = true, name = "fake-ai-server") {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val input = socket.getInputStream()
                        val header = StringBuilder()
                        while (!header.endsWith("\r\n\r\n")) {
                            val value = input.read()
                            check(value >= 0)
                            header.append(value.toChar())
                        }
                        val length = Regex("Content-Length: (\\d+)", RegexOption.IGNORE_CASE)
                            .find(header)?.groupValues?.get(1)?.toInt() ?: 0
                        repeat(length) { input.read() }
                        if (sendPartialBody) {
                            socket.getOutputStream().apply {
                                write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 99999\r\n\r\n{\"id\":\"".toByteArray())
                                flush()
                            }
                        }
                        arrived.complete(Unit)
                        disconnected.complete(input.read() == -1)
                    }
                } catch (e: Exception) {
                    if (arrived.isDone) disconnected.complete(true)
                    else arrived.completeExceptionally(e)
                }
            }
            val request = async(Dispatchers.Default) {
                val config = AiConfig(apiKey = "local-test-only", model = "test-model",
                    baseUrl = "http://127.0.0.1:${server.localPort}/v1")
                if (streaming) AiChatClient.stream(config, "test", "test")
                else AiChatClient.complete(config, "test", "test")
            }
            try {
                withContext(Dispatchers.IO) { arrived.get(5, TimeUnit.SECONDS) }
                // Allow the parser to enter the deliberately incomplete body.
                if (sendPartialBody) delay(100)
                withTimeout(2000) { request.cancelAndJoin() }
                assertTrue(withContext(Dispatchers.IO) { disconnected.get(2, TimeUnit.SECONDS) })
            } finally {
                request.cancel()
                server.close()
                peer.join(1000)
            }
        }
    }

    @Test fun streamingCancellationClosesConnection() { cancellationClosesSocket(false, true) }

    @Test fun cancellationBeforeHeadersClosesConnection() { cancellationClosesSocket(false) }
    @Test fun cancellationDuringBodyParsingClosesConnection() { cancellationClosesSocket(true) }
    private fun successfulResponse(
        streaming: Boolean,
        truncatedAfterDone: Boolean = false,
        collectDuringStream: Boolean = false,
    ): String? = runBlocking {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val reasoningEvent = if (collectDuringStream) {
                "data: {\"id\":\"test\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"test-model\",\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"thinking\"}}]}\n\n"
            } else ""
            val continueResponse = CompletableFuture<Unit>()
            val reply = if (streaming) {
                "data: {\"id\":\"test\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"test-model\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hello\"}}]}\n\ndata: [DONE]\n\n"
            } else {
                "{\"id\":\"test\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"test-model\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"你好\"},\"finish_reason\":\"stop\"}]}"
            }
            val requestHeaders = CompletableFuture<String>()
            thread(isDaemon = true) {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val input = socket.getInputStream()
                    val header = StringBuilder()
                    while (!header.endsWith("\r\n\r\n")) header.append(input.read().toChar())
                    val length = Regex("Content-Length: (\\d+)", RegexOption.IGNORE_CASE)
                        .find(header)?.groupValues?.get(1)?.toInt() ?: 0
                    repeat(length) { input.read() }
                    requestHeaders.complete(header.toString())
                    val bytes = (reasoningEvent + reply).toByteArray(Charsets.UTF_8)
                    val declaredLength = if (truncatedAfterDone) bytes.size + 100 else bytes.size
                    val type = if (streaming) "text/event-stream" else "application/json"
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: $declaredLength\r\nConnection: close\r\n\r\n".toByteArray())
                        if (collectDuringStream) {
                            write(reasoningEvent.toByteArray(Charsets.UTF_8))
                            flush()
                            continueResponse.get(3, TimeUnit.SECONDS)
                            write(reply.toByteArray(Charsets.UTF_8))
                        } else {
                            write(bytes)
                        }
                        flush()
                    }
                }
            }
            val configs = AiConfig(apiKey = "local-test-only", model = "test-model",
                baseUrl = "http://127.0.0.1:${server.localPort}/v1")
            val result = withTimeout(5000) {
                if (streaming) AiChatClient.stream(configs, "test", "test", onReasoning = {
                    if (collectDuringStream) {
                        repeat(8) {
                            System.gc()
                            System.runFinalization()
                            Thread.sleep(25)
                        }
                        continueResponse.complete(Unit)
                    }
                })?.content
                else AiChatClient.complete(configs, "test", "test")
            }
            val headers = requestHeaders.get(1, TimeUnit.SECONDS)
            assertTrue(headers.startsWith("POST /v1/chat/completions "))
            assertTrue(headers.contains("Authorization: Bearer local-test-only", ignoreCase = true))
            result
        }
    }

    @Test fun normalReplyPreservesUnicodeAndAuthentication() {
        assertEquals("你好", successfulResponse(false))
    }
    @Test fun streamingReplyStillWorks() {
        assertEquals("hello", successfulResponse(true))
    }

    @Test fun completedStreamSurvivesConnectionFailureAfterDone() {
        assertEquals("hello", successfulResponse(true, truncatedAfterDone = true))
    }

    @Test fun garbageCollectionDuringReasoningDoesNotCancelStream() {
        assertEquals("hello", successfulResponse(true, collectDuringStream = true))
    }

}
