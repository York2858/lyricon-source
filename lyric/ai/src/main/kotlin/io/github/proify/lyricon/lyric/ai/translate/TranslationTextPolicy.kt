package io.github.proify.lyricon.lyric.ai.translate

import java.text.Normalizer
import java.util.Locale

/** Ignore cosmetic changes when detecting a service that simply echoes its input. */
fun isUsableLyricTranslation(original: String?, translation: String?): Boolean {
    if (translation.isNullOrBlank()) return false
    if (original.isNullOrBlank()) return true // Legacy records may not have originals.
    fun comparable(value: String): String = buildString {
        Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT).codePoints().forEach {
            if (Character.isLetterOrDigit(it)) appendCodePoint(it)
        }
    }
    return comparable(original) != comparable(translation)
}

/** Keep mixed Chinese/foreign lines. Script alone cannot reliably identify Latin languages. */
fun shouldTranslateLyric(text: String, targetLanguage: String?): Boolean {
    val letters = text.codePoints().toArray().filter { Character.isLetter(it) }
    if (letters.isEmpty()) return false
    val target = targetLanguage?.trim()?.takeIf { it.isNotEmpty() }
        ?: Locale.getDefault().toLanguageTag()
    val chinese = target.lowercase(Locale.ROOT).let { language ->
        language == "zh" || language.startsWith("zh-") || language.startsWith("zh_") ||
            language.contains("chinese") || listOf("中文", "汉语", "漢語", "简体", "簡體", "繁体", "繁體")
            .any { target.contains(it) }
    }
    return !chinese || letters.any { Character.UnicodeScript.of(it) != Character.UnicodeScript.HAN }
}
