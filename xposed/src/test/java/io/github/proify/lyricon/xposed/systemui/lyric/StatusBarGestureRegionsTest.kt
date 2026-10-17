package io.github.proify.lyricon.xposed.systemui.lyric

import org.junit.Assert.*
import org.junit.Test

class StatusBarGestureRegionsTest {
    private fun regions() = StatusBarGestureRegions(96, 640, 32, 192, 192).apply {
        setWindowSize(1440, 160)
    }

    @Test fun observedEdgePressStartsAGesture() {
        val regions = regions()
        // Device trace: old padded right edge was 678; DOWN was (680.6582, 105.998).
        regions.rememberLyrics(StatusBarGestureRegions.Bounds(78, 0, 646, 160))
        assertTrue(regions.contains(681, 106))
        assertFalse(regions.contains(681, 161))
    }

    @Test fun shorterLinesAndRepeatedModeSwitchesDoNotShrinkTheTarget() {
        val regions = regions()
        regions.rememberLyrics(StatusBarGestureRegions.Bounds(78, 0, 646, 160))
        val original = regions.lyrics
        repeat(30) {
            regions.rememberLyrics(StatusBarGestureRegions.Bounds(78, 30, 250, 110))
            regions.rememberClock(StatusBarGestureRegions.Bounds(40, 40, 150, 120))
            assertTrue(regions.contains(681, 106))
            assertEquals(original, regions.lyrics)
        }
    }

    @Test fun shortLyricsStillHaveAFullMinimumTargetNearScreenEdge() {
        val regions = regions()
        regions.rememberLyrics(StatusBarGestureRegions.Bounds(40, 40, 120, 120))
        assertEquals(640, regions.lyrics!!.right - regions.lyrics!!.left)
        assertEquals(0, regions.lyrics!!.left)
        assertEquals(160, regions.lyrics!!.bottom)
    }

    @Test fun rotationClearsOldCoordinatesAndKeepsNewRegionsInsideTheWindow() {
        val regions = regions()
        regions.rememberLyrics(StatusBarGestureRegions.Bounds(78, 0, 646, 160))
        regions.setWindowSize(3168, 144)
        assertFalse(regions.contains(681, 106))
        regions.rememberClock(StatusBarGestureRegions.Bounds(3100, 0, 3168, 144))
        assertTrue(regions.contains(3167, 80))
        assertFalse(regions.contains(3168, 80))
        assertFalse(regions.contains(3167, 144))
    }
}
