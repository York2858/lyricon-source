package io.github.proify.lyricon.app.activity.lyric.immersive.halcyon

import io.github.proify.lyricon.app.activity.lyric.immersive.halcyon.model.LyricLine
import io.github.proify.lyricon.app.activity.lyric.immersive.halcyon.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine

internal fun List<RichLyricLine>.toHalcyonLyrics(): List<LyricLine> = mapIndexed { lineIndex, source ->
    val lineEnd = source.end.takeIf { it > source.begin }
        ?: source.duration.takeIf { it > 0 }?.let { source.begin + it }
        ?: getOrNull(lineIndex + 1)?.begin?.takeIf { it > source.begin }
    fun words(values: List<io.github.proify.lyricon.lyric.model.LyricWord>?) = values.orEmpty().mapIndexedNotNull { index, word ->
        val text = word.text.orEmpty().takeIf(String::isNotEmpty) ?: return@mapIndexedNotNull null
        val end = when {
            word.end > word.begin -> word.end
            word.duration > 0 -> word.begin + word.duration
            word.end == word.begin -> word.end
            else -> values?.getOrNull(index + 1)?.begin?.takeIf { it > word.begin }
                ?: lineEnd?.takeIf { it > word.begin } ?: word.begin
        }
        LyricWord(text, word.begin, end)
    }
    fun display(text: String?, values: List<io.github.proify.lyricon.lyric.model.LyricWord>?): String {
        val authored = values.orEmpty().joinToString("") { it.text.orEmpty() }.trimEnd()
        val supplied = text?.trimEnd()?.takeIf(String::isNotBlank) ?: return authored
        // Some providers insert spaces between Latin timing spans when building the
        // line text. If only whitespace differs, the timed spans retain the real spelling.
        return if (authored == supplied ||
            authored.isNotBlank() && authored.filterNot(Char::isWhitespace) == supplied.filterNot(Char::isWhitespace)
        ) authored else supplied
    }
    val secondaryIsReading = !source.roma.isNullOrBlank() && source.secondary?.trim() == source.roma?.trim()
    LyricLine(
        timeMs = source.begin, endMs = lineEnd,
        text = display(source.text, source.words),
        words = words(source.words),
        translation = display(source.translation, source.translationWords).takeIf(String::isNotBlank),
        pronunciation = if (secondaryIsReading) display(source.roma, source.secondaryWords) else source.roma?.trimEnd(),
        pronunciationWords = if (secondaryIsReading) words(source.secondaryWords) else emptyList(),
        agent = if (source.isAlignedRight) "v2" else source.metadata?.getString("agent"),
        agentName = source.metadata?.getString("agentName"),
        backgroundText = if (secondaryIsReading) null else display(source.secondary, source.secondaryWords).takeIf(String::isNotBlank),
        backgroundWords = if (secondaryIsReading) emptyList() else words(source.secondaryWords),
        backgroundStartMs = if (secondaryIsReading) null else source.secondaryWords?.minOfOrNull { it.begin },
        backgroundEndMs = if (secondaryIsReading) null else source.secondaryWords?.maxOfOrNull { it.end },
        isTtml = source.metadata?.getBoolean("isTtml") == true
    )
}
