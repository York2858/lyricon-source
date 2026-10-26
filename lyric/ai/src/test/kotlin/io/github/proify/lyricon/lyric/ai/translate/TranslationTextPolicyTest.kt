package io.github.proify.lyricon.lyric.ai.translate

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class TranslationTextPolicyTest {
    @Test fun chineseLinesAreSkippedButForeignAndMixedLinesKeepTheirText() {
        listOf("中文", "简体中文", "繁體中文", "Chinese (Simplified)", "zh-CN", "zh_Hant").forEach { target ->
            listOf("我得走了", "我們在２０２６年相遇！🌙", "𠀀你好").forEach {
                assertFalse("$target: $it", shouldTranslateLyric(it, target))
            }
            listOf("I gotta go now", "我得走了 I gotta go now", "日语かな歌词", "사랑해", "我爱ＡＢＣ").forEach {
                assertTrue("$target: $it", shouldTranslateLyric(it, target))
            }
        }
        listOf("", "2026", "２０２６", "……❤️").forEach { assertFalse(shouldTranslateLyric(it, "en")) }
    }

    @Test fun chineseIsStillTranslatedWhenTheTargetIsNotChinese() {
        listOf("en", "英语", "ja", "日语").forEach { assertTrue(shouldTranslateLyric("我得走了", it)) }
    }

    @Test fun unspecifiedTargetUsesTheCurrentSystemLanguage() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
            assertFalse(shouldTranslateLyric("我得走了", null))
            Locale.setDefault(Locale.ENGLISH)
            assertTrue(shouldTranslateLyric("我得走了", " "))
        } finally { Locale.setDefault(previous) }
    }

    @Test fun copiedOriginalsAreRejectedEvenWithCosmeticChanges() {
        listOf("I gotta go now", " I GOTTA GO NOW! ", "I  gotta\ngo now", "Ｉ ｇｏｔｔａ ｇｏ ｎｏｗ").forEach {
            assertFalse(isUsableLyricTranslation("I gotta go now", it))
        }
        assertFalse(isUsableLyricTranslation("我得走了", "我得走了。"))
        assertFalse(isUsableLyricTranslation("Hello", " "))
        assertTrue(isUsableLyricTranslation("𠀀", "𠀁"))
        assertTrue(isUsableLyricTranslation("I gotta go now", "我现在得走了"))
        assertTrue(isUsableLyricTranslation("", "旧记录只有译文"))
    }
}
