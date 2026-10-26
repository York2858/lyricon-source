package io.github.proify.lyricon.xposed.systemui.lyric.processor

import io.github.proify.lyricon.lyric.ai.core.AiConfig
import io.github.proify.lyricon.lyric.ai.core.AiConfigProvider
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.lyric.style.BasicStyle
import io.github.proify.lyricon.lyric.style.LyricStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Calls the production processor. A null probe config prevents every network request. */
class ChineseTranslationSkipTest {
    private class ConfigProbe : AiConfigProvider {
        override val activeConfig = MutableStateFlow<AiConfig?>(null)
        var reads = 0
        override fun getActiveConfig(): AiConfig? { reads++; return null }
    }
    private suspend fun isSkipped(lines: List<String>, enabled: Boolean = true): Boolean {
        val probe = ConfigProbe()
        val song = Song(name = "本地跳过验证", lyrics = lines.map { RichLyricLine(text = it, begin = 0, end = 1000) })
        val result = AiTranslationPostProcessor(probe).process(song, LyricStyle(basicStyle = BasicStyle(
            isAiTranslationEnable = true, isAiTranslationAutoIgnoreChinese = enabled)))
        assertSame(song, result)
        // The Chinese gate returns before reading the translation config.
        // If not skipped, the null probe config returns safely before any translator can run.
        return probe.reads == 0
    }

    @Test fun ordinaryChineseLyricsAreSkippedIncludingTraditionalPunctuationAndEmoji(): Unit = runBlocking {
        val cases = listOf(
            listOf("我把晚风折成一封信", "没有地址也想寄给你"),
            listOf("我把晚風折成一封信", "沒有地址也想寄給你"),
            listOf("你好，世界！", "我爱你……（永远）"),
            listOf("  我爱你  ", "\t你也爱我\n"),
            listOf("我爱你❤️", "晚安🌙"),
            listOf("作词：张三", "作曲：李四", "我愿陪你走到天明"),
            listOf("我愿等你一千年", "爱你一生一世"),
            listOf("我们在2026年相遇"),
            listOf("爱你10000年"),
            listOf("在2026年1月1日，10000次想你。"),
            listOf("2026", "10000")
        )
        cases.forEach { assertTrue("Should skip $it", isSkipped(it)) }
    }

    @Test fun anyForeignLineStillPreventsWholeSongSkipping(): Unit = runBlocking {
        val cases = listOf(
            listOf("我爱你", "I love you"),
            listOf("我 love 你"),
            listOf("2026 I love you"),
            listOf("作词：Alex", "我愿陪你走到天明"),
            listOf("日语かな歌词"),
            listOf("사랑해"),
            listOf("Hello world")
        )
        cases.forEach { assertFalse("Should reach translation config for $it", isSkipped(it)) }
    }

    @Test fun disablingTheSwitchDoesNotSkipChinese(): Unit = runBlocking {
        assertFalse(isSkipped(listOf("我爱你"), enabled = false))
        assertTrue(isSkipped(listOf("我爱你"), enabled = true))
    }

    @Test fun fullwidthLatinAndDigitBlocksAreCurrentlyTreatedAsChinese(): Unit = runBlocking {
        assertTrue(isSkipped(listOf("我爱ＡＢＣ")))
        assertTrue(isSkipped(listOf("我们在２０２６年相遇")))
    }

    @Test fun copiedSourceTranslationsDoNotSuppressTranslationButRealTranslationsDo(): Unit = runBlocking {
        suspend fun configReads(translation: String, extra: List<RichLyricLine> = emptyList()): Int {
            val probe = ConfigProbe()
            val source = Song(name = "已有译文验证", lyrics = listOf(
                RichLyricLine(text = "I gotta go now", translation = translation)) + extra)
            AiTranslationPostProcessor(probe).process(source, LyricStyle(basicStyle = BasicStyle(
                isAiTranslationEnable = true, aiTranslationTargetLanguage = "中文")))
            return probe.reads
        }
        assertEquals(1, configReads("I GOTTA GO NOW!"))
        assertEquals(0, configReads("我现在得走了", listOf(RichLyricLine(text = ""),
            RichLyricLine(text = "2026"), RichLyricLine(text = "我得走了"))))
    }
}
