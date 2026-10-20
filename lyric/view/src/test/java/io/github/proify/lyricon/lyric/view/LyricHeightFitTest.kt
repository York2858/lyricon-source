package io.github.proify.lyricon.lyric.view

import org.junit.Assert.*
import org.junit.Test

class LyricHeightFitTest {
    @Test fun fixedStatusBarFitsBothCompleteAnimatedRows() {
        val main = 86
        val translation = 65
        val height = 80
        val scale = LyricHeightFit.scale(main + translation, height, .8f)
        assertEquals(height.toFloat(), (main + translation) * scale, .0001f)
        assertTrue(main * scale > 0f)
        assertTrue(translation * scale > 0f)
        // Centered layout + centered drawing maps the raw outer edges inside the view.
        val rawTop = (height - main - translation) / 2f
        assertEquals(0f, height / 2f + (rawTop - height / 2f) * scale, .0001f)
        assertEquals(height.toFloat(), height / 2f + (rawTop + main + translation - height / 2f) * scale, .0001f)
    }

    @Test fun roomyFloatingLayoutKeepsTheRequestedScale() {
        assertEquals(.8f, LyricHeightFit.scale(151, 300, .8f), 0f)
        assertEquals(1.5f, LyricHeightFit.scale(100, 150, 1.5f), 0f)
    }

    @Test fun overlappingLinesShareHeightWithoutStarvingTheSecondLine() {
        assertEquals(listOf(56, 24), LyricHeightFit.allocate(listOf(140, 60), 80))
        assertEquals(listOf(0, 40, 40), LyricHeightFit.allocate(listOf(0, 100, 100), 80))
        assertEquals(81, LyricHeightFit.allocate(listOf(83, 63, 61), 81).sum())
    }

    @Test fun emptyOrZeroHeightContainersDoNotDivideByZero() {
        assertEquals(0f, LyricHeightFit.scale(150, 0, 1f), 0f)
        assertEquals(1f, LyricHeightFit.scale(0, 0, 1f), 0f)
        assertEquals(listOf(0, 0), LyricHeightFit.allocate(listOf(0, 0), 80))
        assertEquals(emptyList<Int>(), LyricHeightFit.allocate(emptyList(), 80))
    }
}
