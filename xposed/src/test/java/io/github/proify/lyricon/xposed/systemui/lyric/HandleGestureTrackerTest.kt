package io.github.proify.lyricon.xposed.systemui.lyric

import org.junit.Assert.*
import org.junit.Test

class HandleGestureTrackerTest {
    private fun tracker() = HandleGestureTracker(longPressSlop = 12f, swipeDistance = 28f)

    @Test fun delayedDownAndUpStillRecognizePhysicalLongPress() {
        val tracker = tracker()
        repeat(30) {
            // Both events may be dispatched together after the main thread unblocks.
            tracker.down(100f, 20f)
            assertTrue(tracker.longPressAtUp(102f, 21f, heldForMillis = 900, timeoutMillis = 400))
            assertNull(tracker.up(102f, 21f))
        }
    }

    @Test fun releaseFallbackDoesNotDuplicateOrInventLongPresses() {
        val tracker = tracker()
        tracker.down(100f, 20f)
        assertFalse(tracker.longPressAtUp(100f, 20f, 100, 400))
        assertEquals(HandleGestureTracker.Action.TAP, tracker.up(100f, 20f))
        tracker.down(100f, 20f)
        assertTrue(tracker.longPress())
        assertFalse(tracker.longPressAtUp(100f, 20f, 900, 400))
        assertNull(tracker.up(100f, 20f))
        tracker.down(100f, 20f)
        assertFalse(tracker.longPressAtUp(150f, 20f, 900, 400))
        assertEquals(HandleGestureTracker.Action.SWIPE_RIGHT, tracker.up(150f, 20f))
        tracker.down(100f, 20f)
        tracker.cancel()
        assertFalse(tracker.longPressAtUp(100f, 20f, 900, 400))
    }

    @Test fun repeatedModeSwitchesDoNotNeedThePreviousHiddenViewsUpEvent() {
        val tracker = tracker()
        repeat(30) {
            tracker.down(100f, 20f)
            assertTrue(tracker.move(104f, 22f))
            assertTrue(tracker.longPress())
            // Switching lyric windows can remove the old target before UP reaches it.
        }
        assertNull(tracker.up(104f, 22f))
        tracker.down(100f, 20f)
        assertTrue(tracker.longPress())
    }

    @Test fun consecutiveTapsAllTrigger() {
        val tracker = tracker()
        repeat(20) {
            tracker.down(100f, 100f)
            assertEquals(HandleGestureTracker.Action.TAP, tracker.up(100f, 100f))
        }
    }

    @Test fun fingerJitterStillAllowsTapAndLongPress() {
        val tracker = tracker()
        tracker.down(100f, 100f)
        assertTrue(tracker.move(108f, 104f))
        assertTrue(tracker.longPress())
        assertFalse(tracker.longPress())
        assertNull(tracker.up(108f, 104f))
        tracker.down(100f, 100f)
        tracker.move(118f, 108f)
        assertEquals(HandleGestureTracker.Action.TAP, tracker.up(118f, 108f))
    }

    @Test fun swipeDoesNotAlsoTriggerTapOrLongPress() {
        val tracker = tracker()
        repeat(10) {
            tracker.down(100f, 100f)
            assertFalse(tracker.move(140f, 100f))
            assertFalse(tracker.longPress())
            assertEquals(HandleGestureTracker.Action.SWIPE_RIGHT, tracker.up(140f, 100f))
            assertNull(tracker.up(140f, 100f))
        }
    }

    @Test fun cancellationDoesNotPoisonNextGesture() {
        val tracker = tracker()
        tracker.down(100f, 100f)
        tracker.cancel()
        assertFalse(tracker.longPress())
        assertNull(tracker.up(100f, 100f))
        tracker.down(100f, 100f)
        assertEquals(HandleGestureTracker.Action.SWIPE_LEFT, tracker.up(50f, 100f))
    }

    @Test fun verticalDragDoesNotChangeTracks() {
        val tracker = tracker()
        tracker.down(100f, 100f)
        tracker.move(110f, 160f)
        assertNull(tracker.up(110f, 160f))
    }
}
