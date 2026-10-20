package io.github.proify.lyricon.lyric.view

/** Geometry for fitting complete lyric stacks into a fixed-height status bar. */
internal object LyricHeightFit {
    fun scale(contentHeight: Int, availableHeight: Int, requestedScale: Float): Float =
        if (contentHeight <= 0) requestedScale
        else minOf(requestedScale, availableHeight.coerceAtLeast(0).toFloat() / contentHeight)

    fun allocate(heights: List<Int>, availableHeight: Int): List<Int> {
        val total = heights.sum().toLong()
        if (total <= 0) return heights.map { 0 }
        val available = availableHeight.coerceAtLeast(0).toLong()
        var used = 0L
        var previousEdge = 0L
        return heights.map { height ->
            used += height
            val edge = used * available / total
            (edge - previousEdge).toInt().also { previousEdge = edge }
        }
    }
}
