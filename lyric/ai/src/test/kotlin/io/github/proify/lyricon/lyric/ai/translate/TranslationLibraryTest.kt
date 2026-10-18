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
}
