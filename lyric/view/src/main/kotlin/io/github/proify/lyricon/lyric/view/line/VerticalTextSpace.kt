package io.github.proify.lyricon.lyric.view.line

import io.github.proify.lyricon.lyric.view.line.effect.EMPHASIS_LIFT_EM
import io.github.proify.lyricon.lyric.view.line.effect.SCALE_OVERFLOW_FRACTION
import kotlin.math.abs
import kotlin.math.max

/** Shares the same glyph and motion bounds between measurement and rendering. */
internal object VerticalTextSpace {
    fun naturalHalfHeight(top: Float, ascent: Float, descent: Float, bottom: Float): Float {
        val baselineOffset = -(descent + ascent) / 2f
        return max(abs(top + baselineOffset), abs(bottom + baselineOffset))
    }

    fun motionHalfHeight(top: Float, bottom: Float, textSize: Float, waveLift: Float, emphasis: Boolean): Float {
        // WaveLift's held rise and sine wave can peak at the same time.
        val rise = textSize * (2f * waveLift.coerceAtLeast(0f) + if (emphasis) EMPHASIS_LIFT_EM else 0f)
        val scale = if (emphasis) max(abs(top), abs(bottom)) * SCALE_OVERFLOW_FRACTION else 0f
        return rise + scale
    }

    fun strength(viewHeight: Int, naturalHalfHeight: Float, motionHalfHeight: Float): Float {
        if (motionHalfHeight <= 0f) return 1f
        return ((viewHeight / 2f - naturalHalfHeight) / motionHalfHeight).coerceIn(0f, 1f)
    }
}
