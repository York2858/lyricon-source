package io.github.proify.lyricon.lyric.ai.translate

import org.junit.Assert.*
import org.junit.Test

class TranslationTimingDetectionTest {
    private val line = TranslationTiming(0, 7649, 12255,
        listOf(TranslationWord("Eu vou torcer pela paz", 7649, 12255)))
    private val words = TranslationTiming(0, 7165, 11645, listOf(
        TranslationWord("Eu ", 7165, 7425), TranslationWord("vou ", 7425, 7735),
        TranslationWord("torcer ", 7735, 8875), TranslationWord("pela ", 8875, 9155), TranslationWord("paz", 9155, 11645)))
    private val song = TranslationSongEntry("key", "song", "artist", 2, 46, 0, 1, "中文")

    @Test fun capturedAppleAndKugouTimingsAreClassifiedFromActualFragments() {
        assertFalse(hasTranslationWordTiming(listOf(line)))
        assertTrue(hasTranslationWordTiming(listOf(words)))
    }
    @Test fun whitespaceDuplicateStartsAndInvalidTimesCannotCreateWordTiming() {
        listOf(TranslationWord(" ", 8000, 9000), TranslationWord("word", 7649, 9000),
            TranslationWord("word", 8000, 13000), TranslationWord("word", 8000, 8000)).forEach { extra ->
            assertFalse(hasTranslationWordTiming(listOf(line.copy(words = line.words + extra))))
        }
    }
    @Test fun groupedLabelsDistinguishWordLineMixedAndUnknownRecords() {
        assertEquals("未检测", translationTimingLabel(song))
        assertEquals("逐字", translationTimingLabel(song.copy(wordTimedVersions = 2)))
        assertEquals("逐行", translationTimingLabel(song.copy(lineTimedVersions = 2)))
        assertEquals("逐字 / 逐行", translationTimingLabel(song.copy(wordTimedVersions = 1, lineTimedVersions = 1)))
    }
    @Test fun olderEndpointPayloadsRemainReadableWithoutGuessingTiming() {
        val json = TranslationLibraryContract.json
        val old = json.decodeFromString<TranslationSongEntry>("""{"key":"a","title":"song","artist":"a","versions":1,"lines":1,"favorites":0,"updatedAt":0,"languages":"中文"}""")
        assertEquals("未检测", translationTimingLabel(old))
    }
}
