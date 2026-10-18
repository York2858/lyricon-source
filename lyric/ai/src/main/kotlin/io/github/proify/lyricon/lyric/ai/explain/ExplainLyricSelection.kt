package io.github.proify.lyricon.lyric.ai.explain

/** A gap or instrumental intro has no currently sung line. Keep repeated lines distinct. */
object ExplainLyricSelection {
    fun currentIndex(starts: List<Long>, ends: List<Long>, position: Long): Int =
        starts.indices.lastOrNull { index ->
            starts[index] <= position && position < (ends.getOrNull(index) ?: starts[index])
        } ?: -1
}
