package io.github.proify.lyricon.xposed.systemui.ai.translate

import io.github.proify.lyricon.lyric.ai.translate.isUsableLyricTranslation
import io.github.proify.lyricon.lyric.ai.translate.shouldTranslateLyric
import io.github.proify.lyricon.lyric.model.Song

internal object AiTranslationPolicy {
    fun requestItems(texts: List<String>, options: AiTranslationOptions, song: Song? = null): List<TranslationRequestItem> =
        texts.mapIndexedNotNull { index, text ->
            val source = text.trim()
            val existing = song?.lyrics?.getOrNull(index)?.translation
            if (shouldTranslateLyric(source, options.targetLanguage) && !isUsableLyricTranslation(source, existing))
                TranslationRequestItem(index, source)
            else null
        }
}
