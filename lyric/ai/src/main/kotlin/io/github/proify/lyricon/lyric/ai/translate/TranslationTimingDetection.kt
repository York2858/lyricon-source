package io.github.proify.lyricon.lyric.ai.translate

/** The label describes the saved original; AI translations retain sentence timing. */
fun hasTranslationWordTiming(timing: List<TranslationTiming>): Boolean = timing.any { line ->
    if (line.begin < 0 || line.end <= line.begin) return@any false
    var firstBegin: Long? = null
    line.words.any words@{ word ->
        if (word.text.isBlank() || word.begin < line.begin || word.end <= word.begin || word.end > line.end)
            return@words false
        val previous = firstBegin
        if (previous == null) firstBegin = word.begin
        previous != null && previous != word.begin
    }
}

fun translationTimingLabel(song: TranslationSongEntry): String = when {
    song.wordTimedVersions > 0 && song.lineTimedVersions > 0 -> "逐字 / 逐行"
    song.wordTimedVersions > 0 -> "逐字"
    song.lineTimedVersions > 0 -> "逐行"
    else -> "未检测"
}
