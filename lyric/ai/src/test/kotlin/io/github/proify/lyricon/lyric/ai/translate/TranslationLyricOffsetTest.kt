package io.github.proify.lyricon.lyric.ai.translate

import org.junit.Assert.*
import org.junit.Test

class TranslationLyricOffsetTest {
    @Test fun absoluteOffsetsShiftWordsAndLinesWithoutAccumulating() {
        val original = TranslationTiming(0, 1000, 2000, listOf(TranslationWord("hello", 1100, 1900)))
        assertEquals(1300L, original.withLyricOffset(300).begin)
        assertEquals(1400L, original.withLyricOffset(300).words.single().begin)
        assertEquals(700L, original.withLyricOffset(-300).begin)
        assertEquals(original, original.withLyricOffset(0))
        assertEquals(1000L, original.begin)
    }

    @Test fun differentLyricsOrRecordingsDoNotShareAnOffset() {
        val times = listOf(TranslationTiming(0, 1000, 2000))
        val key = translationTimelineKey("Song", "Artist", listOf("hello"), times)
        assertEquals(key, translationTimelineKey(" song ", "artist", listOf(" hello "), times))
        assertNotEquals(key, translationTimelineKey("Song", "Other", listOf("hello"), times))
        assertNotEquals(key, translationTimelineKey("Song", "Artist", listOf("goodbye"), times))
        assertNotEquals(key, translationTimelineKey("Song", "Artist", listOf("hello"), listOf(times.single().copy(begin = 1200))))
        assertEquals(key, translationTimelineKey("Song", "Artist", listOf("hello"),
            listOf(times.single().copy(words = listOf(TranslationWord("hello", 1100, 1900))))))
    }
}
