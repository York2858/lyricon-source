package io.github.proify.lyricon.app.activity.lyric.immersive.halcyon

import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import org.junit.Assert.*
import org.junit.Test

class LyriconAdapterTest {
    @Test fun aSingleEnhancedSpanDoesNotStartEveryEnglishWordAtTheSameTime() {
        listOf(12684L to 13490L, 16493L to 17764L).forEach { (start, end) ->
            val line = listOf(RichLyricLine(begin = start, end = end, text = "Who wanna catch dis?",
                words = listOf(LyricWord(begin = start, end = end, text = "Who wanna catch dis? "))))
                .toHalcyonLyrics().single()
            val rendered = line.words.moveLeadingSpacesToPreviousWord().toAppleMusicRenderWords(line.text, 1200)
            assertEquals("Who wanna catch dis?", rendered.joinToString("") { it.word.text })
            assertEquals(4, rendered.size)
            assertEquals(start, rendered.first().word.startMs)
            assertEquals(end, rendered.last().word.endMs)
            assertTrue(rendered.zipWithNext().all { (a, b) -> a.word.endMs == b.word.startMs })
            assertEquals(0f, rendered[1].karaokeProgress(start + 10, true))
            assertEquals(0f, rendered.last().karaokeProgress(start + (end - start) / 2, true))
        }
    }
    @Test fun latinTimingSpansOnlySeparateWhenTheSourceHasASeparator() {
        fun render(first: String, second: String, supplied: String = "peo ple"): Pair<String, List<AppleMusicRenderWord>> {
            val line = listOf(RichLyricLine(
                begin = 1000, end = 2000, text = supplied,
                words = listOf(
                    LyricWord(begin = 1000, end = 1400, text = first),
                    LyricWord(begin = 1400, end = 2000, text = second)
                )
            )).toHalcyonLyrics().single()
            return line.text to line.words.moveLeadingSpacesToPreviousWord().toAppleMusicRenderWords(line.text, 1200)
        }
        listOf(
            Triple("peo", "ple", "people"),
            Triple("peo ", "ple", "peo ple"),
            Triple("peo", " ple", "peo ple"),
            Triple("peo ", " ple", "peo  ple")
        ).forEach { (first, second, expected) ->
            val (text, rendered) = render(first, second)
            assertEquals(expected, text)
            assertEquals(expected, rendered.joinToString("") { it.word.text })
            assertEquals(expected.contains(' '), lyricUnitBreakAllowedBefore(rendered.map { it.word.text })[1])
            assertEquals(listOf(1000L, 1400L), rendered.map { it.word.startMs })
        }
        assertEquals("people!", render("peo", "ple", "peo ple!").second.joinToString("") { it.word.text })
    }

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

    @Test fun enhancedLrcKeepsTheOriginalWordTimesWhenWhitespaceIsNormalized() {
        // [00:01.000]<00:01.000>stay<00:01.400> <00:01.600>with<00:02.000> me <00:03.000>
        val source = RichLyricLine(begin = 1000, end = 3000, text = "stay with me  ",
            words = listOf(
                LyricWord(begin = 1000, end = 1400, text = "stay"),
                LyricWord(begin = 1400, end = 1600, text = " "),
                LyricWord(begin = 1600, end = 2000, text = "with"),
                LyricWord(begin = 2000, end = 3000, text = " me ")
            ))
        val line = listOf(source).toHalcyonLyrics().single()
        val rendered = line.words.moveLeadingSpacesToPreviousWord().toAppleMusicRenderWords(line.text, 1200)
        assertEquals("stay with me", rendered.joinToString("") { it.word.text })
        assertEquals(listOf(1000L, 1600L, 2000L), rendered.map { it.word.startMs })
        assertEquals(listOf(1f, .5f, 0f), rendered.map { it.karaokeProgress(1800, active = true) })
    }

    @Test fun aTextMismatchDoesNotDropTheSourceTiming() {
        val source = RichLyricLine(begin = 1000, end = 2000, text = "Sing along",
            words = listOf(LyricWord(begin = 1000, end = 1400, text = "sing "),
                LyricWord(begin = 1400, end = 2000, text = "along  ")))
        val line = listOf(source).toHalcyonLyrics().single()
        val rendered = line.words.moveLeadingSpacesToPreviousWord().toAppleMusicRenderWords(line.text, 1200)
        assertEquals("sing along", rendered.joinToString("") { it.word.text })
        assertEquals(listOf(1000L, 1400L), rendered.map { it.word.startMs })
    }

    @Test fun lineTimedLrcSweepsUntilTheNextLineOrItsExplicitEnd() {
        val lines = listOf(
            RichLyricLine(begin = 1000, text = "hold this note "), // plain LRC
            RichLyricLine(begin = 3000, end = 4500, text = "留住这刻  "), // LRC with a line-end tag
            RichLyricLine(begin = 5000, text = "next")
        ).toHalcyonLyrics()
        listOf(2000L, 3750L).forEachIndexed { index, midpoint ->
            val line = lines[index]
            assertTrue(line.words.isEmpty())
            val parts = lineSweepWords(line.text, line.timeMs..line.endMs!!)
            assertEquals(line.text, parts.joinToString("") { it.word.text })
            assertEquals(line.timeMs, parts.first().word.startMs)
            assertEquals(listOf(3000L, 4500L)[index], parts.last().word.endMs)
            assertEquals(1f, parts.first().karaokeProgress(midpoint, active = true))
            assertEquals(0f, parts.last().karaokeProgress(midpoint, active = true))
        }
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
