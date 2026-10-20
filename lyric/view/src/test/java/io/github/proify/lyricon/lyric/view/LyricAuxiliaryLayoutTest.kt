package io.github.proify.lyricon.lyric.view

import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.LyricWord
import org.junit.Assert.*
import org.junit.Test

class LyricAuxiliaryLayoutTest {
    private val source = RichLyricLine(begin = 1000, end = 5000, text = "原歌词", roma = "romaji",
        translation = "翻译", secondary = "伴唱")
    private fun assembler(layout: AuxiliaryLineLayout) = LyricLineAssembler().apply { this.layout = layout }

    @Test fun statusBarDefaultsToTranslationAndCanPreferRoma() {
        val default = assembler(AuxiliaryLineLayout.TRANSLATION_FIRST)
        val roman = assembler(AuxiliaryLineLayout.ROMA_FIRST)
        assertEquals("翻译", default.buildSecondary(source).line.text)
        assertEquals("romaji", roman.buildSecondary(source).line.text)
        assertNull(default.buildTertiary(source).line.text)
        assertNull(roman.buildTertiary(source).line.text)
        assertEquals("原歌词", roman.buildMain(source).line.text)
    }

    @Test fun eitherStatusPreferenceFallsBackToTheAvailableContent() {
        listOf(AuxiliaryLineLayout.TRANSLATION_FIRST, AuxiliaryLineLayout.ROMA_FIRST).forEach {
            val assembler = assembler(it)
            assertEquals("翻译", assembler.buildSecondary(source.copy(roma = "")).line.text)
            assertEquals("romaji", assembler.buildSecondary(source.copy(translation = "")).line.text)
            assertEquals("伴唱", assembler.buildSecondary(source.copy(roma = null, translation = null)).line.text)
        }
    }

    @Test fun floatingUsesOriginalThenRomaThenTranslationEvenWhenSecondaryExists() {
        val assembler = assembler(AuxiliaryLineLayout.ROMA_THEN_TRANSLATION)
        assertEquals("原歌词", assembler.buildMain(source).line.text)
        assertEquals("romaji", assembler.buildSecondary(source).line.text)
        assertEquals("翻译", assembler.buildTertiary(source).line.text)
        assertEquals(source.begin, assembler.buildTertiary(source).line.begin)
        assertEquals(source.end, assembler.buildTertiary(source).line.end)
    }

    @Test fun missingOrDisabledContentDoesNotLeaveADuplicateThirdLine() {
        val assembler = assembler(AuxiliaryLineLayout.ROMA_THEN_TRANSLATION)
        assertEquals("翻译", assembler.buildSecondary(source.copy(roma = null)).line.text)
        assertNull(assembler.buildTertiary(source.copy(roma = null)).line.text)
        assertEquals("romaji", assembler.buildSecondary(source.copy(translation = null)).line.text)
        assertNull(assembler.buildTertiary(source.copy(translation = null)).line.text)
        assembler.updateFlags(displayTranslation = true, displayRoma = false, enableRelativeProgress = false, enableRelativeHighlight = false)
        assertEquals("翻译", assembler.buildSecondary(source).line.text)
        assertNull(assembler.buildTertiary(source).line.text)
    }

    @Test fun romaAndTranslationBothUseTheHighlightAndMotionRenderer() {
        AuxiliaryLineLayout.entries.forEach { layout ->
            val assembler = assembler(layout)
            assertFalse(assembler.buildSecondary(source).isScrollOnly)
            assertFalse(assembler.buildSecondary(source.copy(roma = null)).isScrollOnly)
            assertFalse(assembler.buildSecondary(source.copy(translation = null)).isScrollOnly)
        }
    }

    @Test fun realRomanizationTimingsArePreservedWhenAvailable() {
        val timed = listOf(LyricWord(begin = 1200, end = 1700, text = "ro"),
            LyricWord(begin = 2300, end = 3800, text = "maji"))
        val source = source.copy(secondary = source.roma, secondaryWords = timed)
        val result = assembler(AuxiliaryLineLayout.ROMA_THEN_TRANSLATION).buildSecondary(source)
        assertSame(timed, result.line.words)
        assertFalse(result.isScrollOnly)
    }

    @Test fun unrelatedSecondaryTimingsAreNotAssignedToRomanization() {
        val source = source.copy(secondaryWords = listOf(LyricWord(begin = 2000, end = 2500, text = "伴唱")))
        val result = assembler(AuxiliaryLineLayout.ROMA_THEN_TRANSLATION).buildSecondary(source)
        assertEquals("romaji", result.line.words?.single()?.text)
        assertEquals(source.begin, result.line.words?.single()?.begin)
    }
}
