package io.github.proify.lyricon.xposed.systemui.lyric

/** Window-relative touch regions must not shrink when a shorter lyric replaces a longer one. */
internal class StatusBarGestureRegions(
    private val lyricPadding: Int,
    private val minimumLyricWidth: Int,
    private val clockPadding: Int,
    private val minimumClockWidth: Int,
    private val minimumHeight: Int
) {
    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        fun contains(x: Int, y: Int) = x >= left && x < right && y >= top && y < bottom
        fun union(other: Bounds) = Bounds(minOf(left, other.left), minOf(top, other.top),
            maxOf(right, other.right), maxOf(bottom, other.bottom))
    }

    private var width = 0
    private var height = 0
    var lyrics: Bounds? = null
        private set
    var clock: Bounds? = null
        private set

    fun setWindowSize(width: Int, height: Int) {
        if (this.width == width && this.height == height) return
        this.width = width
        this.height = height
        lyrics = null
        clock = null
    }

    fun rememberLyrics(bounds: Bounds) {
        val expanded = expand(bounds, lyricPadding, minimumLyricWidth) ?: return
        lyrics = lyrics?.union(expanded) ?: expanded
    }

    fun rememberClock(bounds: Bounds) {
        clock = expand(bounds, clockPadding, minimumClockWidth) ?: return
    }

    fun contains(x: Int, y: Int): Boolean =
        lyrics?.contains(x, y) == true || clock?.contains(x, y) == true

    private fun expand(bounds: Bounds, padding: Int, minimumWidth: Int): Bounds? {
        if (width <= 0 || height <= 0 || bounds.right <= bounds.left || bounds.bottom <= bounds.top) return null
        val targetWidth = maxOf(minimumWidth, bounds.right - bounds.left + padding * 2).coerceAtMost(width)
        val targetHeight = maxOf(minimumHeight, bounds.bottom - bounds.top).coerceAtMost(height)
        val left = ((bounds.left + bounds.right - targetWidth) / 2).coerceIn(0, width - targetWidth)
        val top = ((bounds.top + bounds.bottom - targetHeight) / 2).coerceIn(0, height - targetHeight)
        return Bounds(left, top, left + targetWidth, top + targetHeight)
    }
}
