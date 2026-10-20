package io.github.proify.lyricon.lyric.ai.translate

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class TranslationDiagnosticsTest {
    @Test fun httpFailuresExplainAuthenticationRateAndQuotaSeparately() {
        assertTrue(TranslationErrors.http(401).reason.contains("API Key"))
        assertTrue(TranslationErrors.http(429, "insufficient_quota").reason.contains("额度"))
        assertTrue(TranslationErrors.http(429, "rate limit").reason.contains("频繁"))
        assertTrue(TranslationErrors.http(404).suggestion.contains("Base URL"))
        assertTrue(TranslationErrors.from(SocketTimeoutException()).reason.contains("超时"))
        assertTrue(TranslationErrors.from(UnknownHostException()).reason.contains("解析"))
    }
    @Test fun secretsAreRemovedBeforeAnErrorCanBeStored() {
        val secret = "test-secret-123"
        val diagnostic = TranslationErrors.http(401, "bad key=$secret Bearer bearer-secret api_key=hiddenKey sk-abcdefghijk", secret)
        assertFalse(diagnostic.reason.contains(secret))
        assertFalse(diagnostic.reason.contains("bearer-secret"))
        assertFalse(diagnostic.reason.contains("hiddenKey"))
        assertFalse(diagnostic.reason.contains("sk-abcdefghijk"))
    }
    @Test fun googleUsesTheSameLanguageAndSegmentRulesAsLyrico() {
        assertEquals("zh-CN", GoogleLyricsTranslator.targetLanguage("简体中文"))
        assertEquals("zh-TW", GoogleLyricsTranslator.targetLanguage("繁體中文"))
        assertEquals("ja", GoogleLyricsTranslator.targetLanguage("日语"))
        assertEquals("你好世界", GoogleLyricsTranslator.parse("""[[["你好","hello",null],["世界","world",null]],null,"en"]"""))
        val batches = GoogleLyricsTranslator.batches(listOf("a".repeat(1000), "b".repeat(800), "c"))
        assertEquals(2, batches.size); assertEquals(2, batches.last().size)
    }
    @Test fun mergedGoogleLinesAreRetriedIndividuallyWithoutChangingOrder() = runBlocking {
        val calls = mutableListOf<String>()
        val result = GoogleLyricsTranslator.translateWith(listOf("one", "two")) { text ->
            calls += text
            when (text) { "one" -> "一"; "two" -> "二"; else -> "合并的一句" }
        }
        assertEquals(listOf("一", "二"), result)
        assertEquals(listOf("one\ntwo", "one", "two"), calls)
    }
}
