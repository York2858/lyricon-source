package io.github.proify.lyricon.app.activity.lyric.immersive.halcyon

import io.github.proify.lyricon.app.activity.lyric.immersive.halcyon.model.LyricLine
import io.github.proify.lyricon.app.activity.lyric.immersive.halcyon.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine

internal fun List<RichLyricLine>.toHalcyonLyrics(): List<LyricLine> = map { source ->
    fun words(values: List<io.github.proify.lyricon.lyric.model.LyricWord>?) = values.orEmpty().map {
        LyricWord(it.text.orEmpty().trim(), it.begin, it.end)
    }
    val secondaryIsReading = !source.roma.isNullOrBlank() && source.secondary?.trim() == source.roma?.trim()
    LyricLine(
        timeMs = source.begin, endMs = source.end.takeIf { it > source.begin },
        text = source.text?.takeIf { it.isNotBlank() } ?: source.words.orEmpty().joinToString("") { it.text.orEmpty() },
        words = words(source.words), translation = source.translation, pronunciation = source.roma,
        pronunciationWords = if (secondaryIsReading) words(source.secondaryWords) else emptyList(),
        agent = if (source.isAlignedRight) "v2" else source.metadata?.getString("agent"),
        agentName = source.metadata?.getString("agentName"),
        backgroundText = if (secondaryIsReading) null else source.secondary?.takeIf { it.isNotBlank() }
            ?: source.secondaryWords?.joinToString("") { it.text.orEmpty() },
        backgroundWords = if (secondaryIsReading) emptyList() else words(source.secondaryWords),
        backgroundStartMs = if (secondaryIsReading) null else source.secondaryWords?.minOfOrNull { it.begin },
        backgroundEndMs = if (secondaryIsReading) null else source.secondaryWords?.maxOfOrNull { it.end },
        isTtml = source.metadata?.getBoolean("isTtml") == true
    )
}
