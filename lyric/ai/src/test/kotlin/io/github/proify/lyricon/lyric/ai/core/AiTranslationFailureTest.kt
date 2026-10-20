package io.github.proify.lyricon.lyric.ai.core

import io.github.proify.lyricon.lyric.ai.translate.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

class AiTranslationFailureTest {
    @Test fun actualHttpFailureRetainsStatusAndRedactsTheCredential() = runBlocking {
        ServerSocket(0).use { server ->
            val worker = thread(isDaemon = true) {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val input = socket.getInputStream(); val headers = StringBuilder()
                    while (!headers.endsWith("\r\n\r\n")) { val byte = input.read(); if (byte < 0) return@use; headers.append(byte.toChar()) }
                    val length = Regex("Content-Length: (\\d+)", RegexOption.IGNORE_CASE).find(headers)?.groupValues?.get(1)?.toInt() ?: 0
                    repeat(length) { input.read() }
                    val body = """{"error":{"message":"invalid local-secret-key","type":"authentication_error"}}""".toByteArray()
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 401 Unauthorized\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(body); flush()
                    }
                }
            }
            try {
                AiChatClient.completeOrThrow(AiConfig(apiKey = "local-secret-key", model = "fixture",
                    baseUrl = "http://127.0.0.1:${server.localPort}/v1"), "test", "test")
                fail("failure was swallowed")
            } catch (e: TranslationFailure) {
                assertEquals(401, e.diagnostic.httpStatus)
                assertTrue(e.diagnostic.reason.contains("HTTP 401"))
                assertFalse(e.diagnostic.reason.contains("local-secret-key"))
            } finally { worker.join(2000) }
        }
    }
}
