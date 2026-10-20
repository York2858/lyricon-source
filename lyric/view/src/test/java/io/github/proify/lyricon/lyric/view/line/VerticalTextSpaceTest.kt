package io.github.proify.lyricon.lyric.view.line

import io.github.proify.lyricon.lyric.view.line.effect.DrawUnit
import io.github.proify.lyricon.lyric.view.line.effect.EffectParam
import io.github.proify.lyricon.lyric.view.line.effect.UnitTransform
import io.github.proify.lyricon.lyric.view.line.effect.WaveLiftEffect
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.ceil

class VerticalTextSpaceTest {
    @Test fun fullSizeAnimatedGlyphsFitAboveAndBelowTheCenteredBaseline() {
        listOf(24f, 48f, 96f).forEach { size ->
            listOf(.05f, .2f, .5f).forEach { lift ->
                val top = -size * .8f; val bottom = size * .12f
                val ascent = -size; val descent = size * .25f
                val natural = VerticalTextSpace.naturalHalfHeight(top, ascent, descent, bottom)
                val motion = VerticalTextSpace.motionHalfHeight(top, bottom, size, lift, true)
                val height = ceil(2 * (natural + motion)).toInt()
                assertEquals(1f, VerticalTextSpace.strength(height, natural, motion), .0001f)
                val baseline = height / 2f - (ascent + descent) / 2f
                val maximumRise = size * (2 * lift + .03f)
                assertTrue("top edge is clipped", baseline + top * 1.12f - maximumRise >= 0)
                assertTrue("bottom edge is clipped", baseline + bottom * 1.12f <= height)
            }
        }
    }
    @Test fun constrainedHeightUsesOnlyTheSpaceThatActuallyRemains() {
        assertEquals(0f, VerticalTextSpace.strength(40, 20f, 8f), 0f)
        assertEquals(.5f, VerticalTextSpace.strength(48, 20f, 8f), 0f)
        assertEquals(1f, VerticalTextSpace.strength(56, 20f, 8f), 0f)
    }
    @Test fun disabledEffectsDoNotReserveMotionSpace() {
        assertEquals(0f, VerticalTextSpace.motionHalfHeight(-30f, 4f, 40f, 0f, false), 0f)
    }
    @Test fun waveLiftRespectsTheAvailableSpaceForBothScripts() {
        val wave = WaveLiftEffect()
        listOf(true, false).forEach { cjk ->
            val unit = DrawUnit().apply { isCjk = cjk; beginMs = 0; endMs = 1000 }
            val params = EffectParam().apply { textSize = 40f; currentTimeMs = 300 }
            val full = UnitTransform(); params.strength = 1f; wave.apply(unit, params, full)
            assertTrue(full.dy < 0)
            val half = UnitTransform(); params.strength = .5f; wave.apply(unit, params, half)
            assertEquals(full.dy / 2, half.dy, .0001f)
            val zero = UnitTransform(); params.strength = 0f; wave.apply(unit, params, zero)
            assertEquals(0f, zero.dy, 0f)
        }
    }
}
