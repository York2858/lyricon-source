package io.github.proify.lyricon.lyric.view.line

internal fun glowAlphaByte(alpha: Float): Int = (alpha.coerceIn(0f, 1f) * 255f).toInt()
