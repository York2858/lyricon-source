package io.github.proify.lyricon.lyric.ai.explain

import org.junit.Assert.assertEquals
import org.junit.Test

class ExplainLyricSelectionTest {
    private val starts = listOf(1000L, 3000L, 6000L)
    private val ends = listOf(2000L, 5000L, 7000L)
    @Test fun introAndGapsDoNotPretendToBeSungLyrics() {
        listOf(0L, 999L, 2000L, 2500L, 5000L, 7000L).forEach {
            assertEquals(-1, ExplainLyricSelection.currentIndex(starts, ends, it))
        }
    }
    @Test fun boundariesAndBackwardSeeksSelectTheRightOccurrence() {
        assertEquals(0, ExplainLyricSelection.currentIndex(starts, ends, 1000))
        assertEquals(1, ExplainLyricSelection.currentIndex(starts, ends, 4999))
        assertEquals(2, ExplainLyricSelection.currentIndex(starts, ends, 6000))
        assertEquals(0, ExplainLyricSelection.currentIndex(starts, ends, 1500))
    }
    @Test fun overlappingLinesUseTheMostRecentStart() {
        assertEquals(1, ExplainLyricSelection.currentIndex(listOf(0L, 1000L), listOf(5000L, 2000L), 1200))
    }
    @Test fun absentOrIncompleteTimingDoesNotCrash() {
        assertEquals(-1, ExplainLyricSelection.currentIndex(emptyList(), emptyList(), 1000))
        assertEquals(-1, ExplainLyricSelection.currentIndex(listOf(1000L), emptyList(), 1000))
    }
}
