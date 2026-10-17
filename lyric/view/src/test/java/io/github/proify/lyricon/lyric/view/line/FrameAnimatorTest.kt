package io.github.proify.lyricon.lyric.view.line

import org.junit.Assert.*
import org.junit.Test

class FrameAnimatorTest {
    @Test fun idleRendererNeverSchedulesFrames() {
        var posted = 0
        val animator = FrameAnimator({ false }, {}, { true }, { false }, { posted++ }, {})
        animator.startIfNeeded()
        assertEquals(0, posted)
        assertFalse(animator.isRunning)
    }

    @Test fun finalChangedFrameInvalidatesAndStops() {
        var needsFrame = true
        var posted = 0
        var invalidations = 0
        val animator = FrameAnimator({ needsFrame = false; true }, { invalidations++ },
            { true }, { needsFrame }, { posted++ }, {})
        animator.startIfNeeded()
        animator.doFrame(100)
        assertEquals(1, posted)
        assertEquals(1, invalidations)
        assertFalse(animator.isRunning)
    }

    @Test fun pendingDelayContinuesDespiteNoVisualChange() {
        var posted = 0
        val animator = FrameAnimator({ false }, { fail("Delay must not redraw") },
            { true }, { true }, { posted++ }, {})
        animator.startIfNeeded()
        animator.doFrame(100)
        assertEquals(2, posted)
        assertTrue(animator.isRunning)
    }

    @Test fun hiddenViewStopsAndResumeResetsElapsedTime() {
        var visible = true
        val deltas = mutableListOf<Long>()
        val animator = FrameAnimator({ deltas.add(it); true }, {}, { visible }, { true }, {}, {})
        animator.startIfNeeded()
        animator.doFrame(100)
        visible = false
        animator.doFrame(200)
        assertFalse(animator.isRunning)
        visible = true
        animator.startIfNeeded()
        animator.doFrame(1_000_000)
        assertEquals(listOf(0L, 0L), deltas)
    }
}
