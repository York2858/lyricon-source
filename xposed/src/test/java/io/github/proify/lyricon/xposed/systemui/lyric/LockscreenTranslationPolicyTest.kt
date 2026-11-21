package io.github.proify.lyricon.xposed.systemui.lyric

import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import org.junit.Assert.assertEquals
import org.junit.Test

class LockscreenTranslationPolicyTest {
    @Test fun chineseLyricsDoNotStartALockscreenRequest() {
        assertEquals(LockscreenTranslationDecision.CHINESE,
            lockscreenTranslationDecision(song("沿途有你，１２３。", "風繼續吹"), null))
    }

    @Test fun lyricsWithNoWordsDoNotStartARequest() {
        assertEquals(LockscreenTranslationDecision.NO_TEXT,
            lockscreenTranslationDecision(song("   ", "123 … ♪"), null))
    }

    @Test fun foreignAndMixedLyricsAreEligible() {
        listOf("The night is quiet", "夜が明ける", "夜のｳﾀ", "想念你, I miss you").forEach { text ->
            assertEquals(LockscreenTranslationDecision.REQUEST, lockscreenTranslationDecision(song(text), null))
        }
    }

    @Test fun usableSourceOrGeneratedTranslationsPreventDuplicateRequests() {
        val original = song("The night is quiet")
        val translated = Song(lyrics = listOf(RichLyricLine(text = "The night is quiet", translation = "夜深人静")))
        val echo = Song(lyrics = listOf(RichLyricLine(text = "The night is quiet", translation = "The night is quiet!")))
        assertEquals(LockscreenTranslationDecision.HAS_TRANSLATION, lockscreenTranslationDecision(translated, null))
        assertEquals(LockscreenTranslationDecision.HAS_TRANSLATION, lockscreenTranslationDecision(original, translated))
        assertEquals(LockscreenTranslationDecision.REQUEST, lockscreenTranslationDecision(echo, null))
    }

    private fun song(vararg lines: String): Song = Song(lyrics = lines.map { RichLyricLine(text = it) })
}
