package io.proify.lyricon.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import io.github.proify.lyricon.app.activity.lyric.albumSectionColors
import io.github.proify.lyricon.app.activity.lyric.albumTrackColors
import io.github.proify.lyricon.app.ai.explain.*
import org.junit.Assert.*
import org.junit.Test

class AlbumReadingSectionsTest {
    private val track = AlbumExplainTrack(number = 3, title = "Holy, Holy")

    @Test fun oldAndNewTitlesAreSuppressedWithoutRemovingTheAnalysis() {
        for (title in listOf("歌曲：3. Holy, Holy", "歌曲 3：Holy, Holy", "# **歌曲 3：Holy, Holy**")) {
            val sections = albumReadingSections("$title\n\n### 歌词与表达\n歌词正文\n### 编曲与律动\n编曲正文\n### 在专辑中的位置\n专辑正文", track)
            assertEquals(listOf(AlbumReadingKind.LYRICS, AlbumReadingKind.ARRANGEMENT, AlbumReadingKind.CONTEXT), sections.map { it.kind })
            assertEquals(listOf("歌词正文", "编曲正文", "专辑正文"), sections.map { it.content })
        }
    }

    @Test fun recognizesLegacyNumberedBoldAndInlineHeadings() {
        val sections = albumReadingSections("1. **歌词与表达：**歌词正文\n\n**二、编曲解读**\n编曲正文\n\n**专辑中的位置**：专辑正文", track)
        assertEquals(listOf(AlbumReadingKind.LYRICS, AlbumReadingKind.ARRANGEMENT, AlbumReadingKind.CONTEXT), sections.map { it.kind })
        assertEquals(listOf("歌词正文", "编曲正文", "专辑正文"), sections.map { it.content })
    }

    @Test fun keepsUnstructuredTextQuotesAndFencedMarkdown() {
        val text = "这是尚未分节的旧解读。\n\n> 编曲与律动\n\n```text\n歌词与表达\n```\n\n~~~\n在专辑中的位置\n~~~"
        val sections = albumReadingSections(text, track)
        assertEquals(listOf(AlbumReadingSection(AlbumReadingKind.GENERAL, text)), sections)
    }

    @Test fun partialHeadingsAndFreshGenerationDoNotReuseOldContent() {
        assertEquals("旧内容", albumReadingSections("### 歌词与表达\n旧内容", track).single().content)
        assertTrue(albumReadingSections("### 编曲与律", track).single().content.contains("编曲与律"))
        val replacement = albumReadingSections("### 编曲与律动\n新内容", track).single()
        assertEquals(AlbumReadingKind.ARRANGEMENT, replacement.kind)
        assertEquals("新内容", replacement.content)
        assertEquals("", albumReadingSections("### 编曲与律动", track).single().content)
    }

    @Test fun copyAndExportUseOneNewTitleAndRetainDiscIdentity() {
        val record = AlbumExplainRecord(title = "专辑", artist = "艺人", tracks = listOf(track.copy(
            content = "歌曲：3. Holy, Holy\n### 歌词与表达\n正文"), track.copy(number = 1, disc = 2, title = "Intro")))
        val text = albumPlainText(record)
        assertEquals(1, Regex("歌曲 3：Holy, Holy").findAll(text).count())
        assertFalse(text.contains("歌曲：3."))
        assertTrue(text.contains("歌词与表达\n正文"))
        assertTrue(text.contains("歌曲 1：Intro（CD 2）"))
    }

    @Test fun bothThemesKeepReadableLabelsAndDistinctAdjacentSongSurfaces() {
        fun contrast(a: Color, b: Color): Double {
            val first = a.luminance().toDouble(); val second = b.luminance().toDouble()
            return (maxOf(first, second) + .05) / (minOf(first, second) + .05)
        }
        for (dark in listOf(false, true)) {
            val palettes = (0..5).map { albumTrackColors(it, dark) }
            assertEquals(6, palettes.map { it.background }.distinct().size)
            assertEquals(palettes.first(), albumTrackColors(6, dark))
            for (palette in palettes + AlbumReadingKind.entries.map { albumSectionColors(it, dark) }) {
                assertTrue("Unreadable label: $dark $palette", contrast(palette.background, palette.accent) >= 4.5)
                assertTrue(contrast(palette.background, if (dark) Color(0xFFE9EDF3) else Color(0xFF111111)) >= 7.0)
            }
        }
    }
}
