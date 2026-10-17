package io.github.proify.lyricon.lyric.view.line

import org.junit.Assert.assertEquals
import org.junit.Test

class GlowAlphaTest {
    @Test fun glowUsesFullByteRange() {
        assertEquals(0, glowAlphaByte(0f))
        assertEquals(127, glowAlphaByte(0.5f))
        assertEquals(255, glowAlphaByte(1f))
    }
    @Test fun glowClampsBeforeScaling() {
        assertEquals(0, glowAlphaByte(-1f))
        assertEquals(255, glowAlphaByte(2f))
    }
}
