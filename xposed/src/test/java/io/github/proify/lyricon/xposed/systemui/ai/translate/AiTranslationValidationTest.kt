package io.github.proify.lyricon.xposed.systemui.ai.translate

import io.github.proify.lyricon.lyric.ai.core.AiConfig
import io.github.proify.lyricon.lyric.ai.translate.TranslationFailure
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

class AiTranslationValidationTest {
    private val config = AiConfig(apiKey = "test", model = "test", baseUrl = "https://example.invalid")
    private val options = AiTranslationOptions(targetLanguage = "中文")
    private val texts = listOf("我得走了", "I gotta go now", "我得走了 I gotta go now", "2026", "hello")
    private val song = Song(lyrics = texts.mapIndexed { i, text ->
        RichLyricLine(text = text, translation = if (i == 4) "你好" else null)
    })
    private suspend fun assertFailure(code: String, action: suspend () -> Any?) {
        try { action(); fail("Expected $code") }
        catch (e: TranslationFailure) { assertEquals(code, e.diagnostic.code) }
    }

    @Test fun bothEnginesOnlyRequestMissingForeignLinesAndKeepOriginalIndices() = runBlocking {
        val ai = AiTranslationRequester.request(config, options, song, texts) { _, _, payload ->
            assertTrue(payload.contains("\"index\":1")); assertTrue(payload.contains("\"index\":2"))
            listOf(0, 3, 4).forEach { assertFalse(payload.contains("\"index\":$it")) }
            """{"translated":[{"index":1,"tran":"我现在得走了"},{"index":2,"tran":"我得走了，现在必须离开"}]}"""
        }
        val google = AiTranslationRequester.requestGoogle(options, texts, song) { _, _, requested ->
            assertEquals(listOf(texts[1], texts[2]), requested)
            """["我现在得走了","我得走了，现在必须离开"]"""
        }
        assertEquals(listOf(1, 2), ai!!.map { it.index })
        assertEquals(ai, google)
    }

    @Test fun allChineseOrAlreadyTranslatedInputNeverCallsEitherService() = runBlocking {
        val readyTexts = listOf(texts[0], texts[4])
        val ready = Song(lyrics = listOf(song.lyrics!![0], song.lyrics!![4]))
        assertTrue(AiTranslationRequester.request(config, options, ready, readyTexts) { _, _, _ ->
            fail("AI should not be called"); ""
        }!!.isEmpty())
        assertTrue(AiTranslationRequester.requestGoogle(options, readyTexts, ready) { _, _, _ ->
            fail("Google should not be called"); ""
        }.isEmpty())
    }

    @Test fun echoedResultsAreRejectedByBothEnginesBeforeTheyCanBeCached() = runBlocking {
        assertFailure("UNCHANGED") {
            AiTranslationRequester.request(config, options, texts = listOf("I gotta go now")) { _, _, _ ->
                """{"translated":[{"index":0,"tran":"I GOTTA GO NOW!"}]}"""
            }
        }
        assertFailure("GOOGLE_UNCHANGED") {
            AiTranslationRequester.requestGoogle(options, listOf("I gotta go now")) { _, _, _ -> """["I gotta go now"]""" }
        }
        val cache = AiTranslationCache(4, AtomicInteger(), this)
        cache.saveToDb("copied", listOf(TranslationItem(0, "I gotta go now")), lines = listOf("I gotta go now"))
        assertNull(cache.getFromMemory("copied"))
    }

    @Test fun aMissingForeignLineStillFailsButExplicitSkipsDoNotPretendToBeTranslations() = runBlocking {
        val requests = listOf(TranslationRequestItem(1, "hello"), TranslationRequestItem(4, "la la la"))
        assertFailure("INCOMPLETE") {
            AiTranslationResponseParser.parseValidated("""{"translated":[{"index":1,"tran":"你好"}]}""", requests)
        }
        val valid = AiTranslationResponseParser.parseValidated(
            """{"translated":[{"index":1,"tran":"你好"}],"skipped":[4]}""", requests)
        assertEquals(listOf(TranslationItem(1, "你好")), valid)
        assertFailure("FORMAT") {
            AiTranslationResponseParser.parseValidated("""{"translated":[{"index":1,"tran":"你好"}],"skipped":[1,4]}""", requests)
        }
    }

    @Test fun invalidAiOutputUsesValidGoogleFallback() = runBlocking {
        val result = TranslationFallback.run(true,
            ai = { AiTranslationRequester.request(config, options, texts = listOf("hello")) { _, _, _ ->
                """{"translated":[{"index":0,"tran":"hello"}]}"""
            } },
            google = { AiTranslationRequester.requestGoogle(options, listOf("hello")) { _, _, _ -> """["你好"]""" } })
        assertEquals("UNCHANGED", result.aiError!!.code)
        assertTrue(result.usedGoogle)
        assertEquals(listOf(TranslationItem(0, "你好")), result.items)
    }

    @Test fun duplicatedSourceTranslationIsReplacedButRealSourceTranslationIsPreserved() {
        val source = Song(lyrics = listOf(RichLyricLine(text = "hello", translation = "HELLO!"),
            RichLyricLine(text = "goodbye", translation = "再见")))
        val result = AiTranslationApplicator.apply(source, listOf(TranslationItem(0, "你好"), TranslationItem(1, "拜拜")))
        assertEquals("你好", result.lyrics!![0].translation)
        assertEquals("再见", result.lyrics!![1].translation)
    }

    @Test fun cacheKeysSeparateRequestCoverageAndTheEffectiveSystemLanguage() {
        val missing = Song(lyrics = listOf(RichLyricLine(text = "hello")))
        val supplied = missing.copy(lyrics = listOf(RichLyricLine(text = "hello", translation = "你好")))
        assertNotEquals(AiTranslationKey.calculate(config, options, missing, listOf("hello")),
            AiTranslationKey.calculate(config, options, supplied, listOf("hello")))
        val revised = supplied.copy(lyrics = listOf(RichLyricLine(text = "hello", translation = "您好")))
        assertNotEquals(AiTranslationKey.calculate(config, options, supplied, listOf("hello")),
            AiTranslationKey.calculate(config, options, revised, listOf("hello")))
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.CHINESE)
            val chinese = AiTranslationKey.calculate(config, AiTranslationOptions(), missing, listOf("hello"))
            Locale.setDefault(Locale.ENGLISH)
            assertNotEquals(chinese, AiTranslationKey.calculate(config, AiTranslationOptions(), missing, listOf("hello")))
        } finally { Locale.setDefault(previous) }
    }
}
