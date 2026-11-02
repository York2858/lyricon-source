package io.github.proify.lyricon.app.activity.lyric.immersive.halcyon

import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import org.junit.Assert.*
import org.junit.Test

class LyriconAdapterTest {
    @Test fun sourceWordPaddingDoesNotDisableTimedKaraokeOnTrimmedSentences() {
        val tokens = listOf("Hold", "each", "little", "word", "in", "time")
        val source = RichLyricLine(begin = 1000, end = 7000, text = tokens.joinToString(" "),
            words = tokens.mapIndexed { index, text -> LyricWord(begin = 1000L + index * 1000, end = 2000L + index * 1000, text = "$text ") })
        val line = listOf(source).toHalcyonLyrics().single()
        val rendered = line.words.moveLeadingSpacesToPreviousWord().toAppleMusicRenderWords(line.text, 1200)
        assertEquals(6, rendered.size)
        assertEquals(source.text, rendered.joinToString("") { it.word.text })
        assertEquals(source.words!!.map { it.begin }, rendered.map { it.word.startMs })
        assertEquals(source.words!!.map { it.end }, rendered.map { it.word.endMs })
        assertTrue(source.words!!.last().text!!.endsWith(" "))
    }

    @Test fun sourceTimingsTranslationsAndDuetAlignmentSurviveAdaptation() {
        val source = RichLyricLine(begin = 1000, end = 5000, isAlignedRight = true,
            text = "歌", words = listOf(LyricWord(begin = 1100, end = 4800, text = "歌")), translation = "song", roma = "uta")
        val line = listOf(source).toHalcyonLyrics().single()
        assertEquals(1000L, line.timeMs)
        assertEquals(5000L, line.endMs)
        assertEquals(1100L, line.words.single().startMs)
        assertEquals(4800L, line.words.single().endMs)
        assertEquals("song", line.translation)
        assertEquals("uta", line.pronunciation)
        assertEquals("v2", line.agent)
        assertEquals("歌", source.text)
    }

    @Test fun timedReadingDoesNotBecomeAnotherBackingVocalOrExtendItsDuration() {
        val line = listOf(RichLyricLine(begin = 1000, end = 2000, text = "歌", roma = "うた",
            secondary = "うた", secondaryWords = listOf(LyricWord(begin = 1000, end = 2500, text = "うた"))))
            .toHalcyonLyrics().single()
        assertEquals("うた", line.pronunciationWords.single().text)
        assertNull(line.backgroundText)
        assertTrue(line.backgroundWords.isEmpty())
        assertNull(line.backgroundEndMs)
    }

    @Test fun wordsOnlyPayloadRemainsReadableAndKeepsBackingVocals() {
        val line = listOf(RichLyricLine(begin = 1000, end = 4000,
            words = listOf(LyricWord(begin = 1000, end = 2000, text = "hello "), LyricWord(begin = 2000, end = 3000, text = "world")),
            secondaryWords = listOf(LyricWord(begin = 2500, end = 4000, text = "echo"))))
            .toHalcyonLyrics().single()
        assertEquals("hello world", line.text)
        assertEquals("echo", line.backgroundText)
        assertEquals(2500L, line.backgroundStartMs)
        assertEquals(4000L, line.backgroundEndMs)
    }
}
