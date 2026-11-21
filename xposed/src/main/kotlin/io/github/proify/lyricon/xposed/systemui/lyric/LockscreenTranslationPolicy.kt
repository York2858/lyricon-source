package io.github.proify.lyricon.xposed.systemui.lyric

import io.github.proify.lyricon.lyric.ai.translate.isUsableLyricTranslation
import io.github.proify.lyricon.lyric.ai.translate.shouldTranslateLyric
import io.github.proify.lyricon.lyric.model.Song

internal enum class LockscreenTranslationDecision { NO_TEXT, HAS_TRANSLATION, CHINESE, REQUEST }

internal fun lockscreenTranslationDecision(original: Song, enriched: Song?): LockscreenTranslationDecision {
    val lines = original.lyrics.orEmpty()
    if (lines.none { line -> line.text.orEmpty().codePoints().anyMatch { Character.isLetter(it) } }) {
        return LockscreenTranslationDecision.NO_TEXT
    }
    val hasTranslation = sequenceOf(original, enriched).filterNotNull().any { song ->
        song.lyrics.orEmpty().any { isUsableLyricTranslation(it.text, it.translation) }
    }
    if (hasTranslation) return LockscreenTranslationDecision.HAS_TRANSLATION
    if (lines.none { shouldTranslateLyric(it.text.orEmpty(), "zh") }) return LockscreenTranslationDecision.CHINESE
    return LockscreenTranslationDecision.REQUEST
}
