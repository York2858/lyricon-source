package io.github.proify.lyricon.lyric.ai.translate

import org.junit.Assert.*
import org.junit.Test

class TranslationLibraryTest {
    @Test fun exportKeepsOriginalTranslationAndMetadata() {
        val version = TranslationVersion("id", "夜航", "林屿", "中文", "model", 1, 1, true, true)
        val text = translationText(version, listOf(TranslationLine(4, "the sea waits", "海在等待")))
        assertTrue(text.contains("夜航\n林屿"))
        assertTrue(text.contains("目标语言：中文"))
        assertTrue(text.contains("the sea waits\n海在等待"))
    }
    @Test fun legacyExportDoesNotInventOriginals() {
        val version = TranslationVersion("id", "", "", "", "", 0, 1, false, false)
        val text = translationText(version, listOf(TranslationLine(1, translation = "旧译文")))
        assertTrue(text.contains("未知歌曲（旧记录）"))
        assertTrue(text.endsWith("旧译文"))
    }

    @Test fun sparseTranslationsKeepEverySourceLineAndItsWordTiming() {
        val words = listOf(TranslationWord("我得", 1000, 1400), TranslationWord("走了", 1400, 2000))
        val timing = mapOf(0 to TranslationTiming(0, 1000, 2000, words), 1 to TranslationTiming(1, 3000, 4000))
        val rows = translationLibraryLines(listOf("我得走了", "I gotta go now", ""), mapOf(1 to "我现在得走了"), timing)
        assertEquals(listOf(0, 1), rows.map { it.index })
        assertEquals("", rows[0].translation)
        assertEquals(words, rows[0].words)
        assertEquals("我现在得走了", rows[1].translation)
        val oldCache = translationLibraryLines(listOf("我得走了", "I gotta go now"),
            mapOf(0 to "我得走了", 1 to "I GOTTA GO NOW!"), timing)
        assertTrue(oldCache.all { it.translation.isEmpty() })
        assertEquals(rows.map { it.original }, oldCache.map { it.original })
    }
}
