package io.github.proify.lyricon.xposed.systemui.lyric

import org.junit.Assert.*
import org.junit.Test

class StatusBarGestureRegionsTest {
    private fun regions() = StatusBarGestureRegions(48, 0, 16, 192, 192).apply {
        setWindowSize(1440, 160)
    }

    @Test fun observedEdgePressStartsAGesture() {
        val regions = regions()
        // Device trace: old padded right edge was 678; DOWN was (680.6582, 105.998).
        regions.rememberLyrics(StatusBarGestureRegions.Bounds(78, 0, 646, 160))
        assertTrue(regions.contains(681, 106))
        assertFalse(regions.contains(681, 161))
    }

    @Test fun shorterTargetReplacesOldBoundsWithoutLeavingAGiantRecoveryArea() {
        val regions = regions()
        regions.rememberLyrics(StatusBarGestureRegions.Bounds(78, 0, 646, 160))
        repeat(30) {
            regions.rememberLyrics(StatusBarGestureRegions.Bounds(78, 30, 250, 110))
            regions.rememberClock(StatusBarGestureRegions.Bounds(40, 40, 150, 120))
            assertTrue(regions.contains(260, 106))
            assertFalse(regions.contains(681, 106))
        }
    }

    @Test fun shortLyricsOnlyAddLimitedEdgeTolerance() {
        val regions = regions()
        regions.rememberLyrics(StatusBarGestureRegions.Bounds(40, 40, 120, 120))
        assertEquals(176, regions.lyrics!!.right - regions.lyrics!!.left)
        assertEquals(0, regions.lyrics!!.left)
        assertEquals(160, regions.lyrics!!.bottom)
    }

    @Test fun movingLyricsDoesNotJoinTheGapBetweenOldAndNewPositions() {
        val regions = regions()
        regions.rememberLyrics(StatusBarGestureRegions.Bounds(20, 0, 180, 160))
        regions.rememberLyrics(StatusBarGestureRegions.Bounds(400, 0, 560, 160))
        assertFalse(regions.contains(100, 80))
        assertFalse(regions.contains(300, 80))
        assertTrue(regions.contains(450, 80))
    }

    @Test fun capsuleAndSystemIconsOverrideEvenAnOversizedLyricTarget() {
        val regions = regions()
        regions.rememberLyrics(StatusBarGestureRegions.Bounds(0, 0, 1440, 160))
        regions.setExclusions(listOf(StatusBarGestureRegions.Bounds(600, 0, 900, 160),
            StatusBarGestureRegions.Bounds(1080, 0, 1440, 160)))
        assertTrue(regions.contains(300, 80))
        assertFalse(regions.contains(700, 80))
        assertFalse(regions.contains(1300, 80))
        // The same exclusions apply to the remembered recovery target when lyrics hide.
        regions.rememberClock(StatusBarGestureRegions.Bounds(630, 20, 750, 140))
        assertFalse(regions.contains(700, 80))
    }

    @Test fun absentOrEmptyClockDropsItsObsoleteBounds() {
        val regions = regions()
        regions.rememberClock(StatusBarGestureRegions.Bounds(1000, 0, 1200, 160))
        assertTrue(regions.contains(1100, 80))
        regions.rememberClock(null)
        assertFalse(regions.contains(1100, 80))
        regions.rememberClock(StatusBarGestureRegions.Bounds(1000, 0, 1200, 160))
        regions.rememberClock(StatusBarGestureRegions.Bounds(0, 0, 0, 0))
        assertFalse(regions.contains(1100, 80))
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
