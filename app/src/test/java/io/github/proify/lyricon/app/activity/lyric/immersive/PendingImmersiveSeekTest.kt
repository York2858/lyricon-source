package io.github.proify.lyricon.app.activity.lyric.immersive

import org.junit.Assert.*
import org.junit.Test

class PendingImmersiveSeekTest {
    private val seek = PendingImmersiveSeek("track-a", 90_000L, 10_000L)

    @Test fun stalePlayingSampleDoesNotUndoAJump() {
        assertTrue(seek.shouldHold("track-a", 30_200L, true, 10_250L))
        assertFalse(seek.shouldHold("track-a", 90_250L, true, 10_250L))
    }

    @Test fun pausedSampleDoesNotConfirmResumeEvenAtTheRequestedPosition() {
        assertTrue(seek.shouldHold("track-a", 90_000L, false, 10_500L))
        assertFalse(seek.shouldHold("track-a", 90_500L, true, 10_500L))
    }

    @Test fun ignoredSeekEventuallyFallsBackToTheRealPlayerInsteadOfFakingProgress() {
        assertFalse(seek.shouldHold("track-a", 31_000L, true, 13_000L))
        assertFalse(seek.isConfirmed(31_000L, true, 13_000L))
    }

    @Test fun trackChangeImmediatelyReleasesOldSeek() {
        assertFalse(seek.shouldHold("track-b", 200L, true, 10_250L))
    }
}
