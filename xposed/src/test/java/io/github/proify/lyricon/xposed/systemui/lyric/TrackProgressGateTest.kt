package io.github.proify.lyricon.xposed.systemui.lyric

import org.junit.Assert.*
import org.junit.Test

class TrackProgressGateTest {
    @Test fun newTrackProgressWaitsForMatchingLyrics() {
        val gate = TrackProgressGate()
        gate.updateMedia("First", "Artist")
        assertTrue(gate.acceptLyrics("First", "Artist"))
        gate.updateMedia("Second", "Artist")
        assertTrue(gate.waitingForLyrics)
        assertFalse(gate.acceptLyrics("First", "Artist")) // late AI completion
        assertTrue(gate.acceptLyrics("Second", "Artist"))
        assertFalse(gate.waitingForLyrics)
    }

    @Test fun rapidSkipsDoNotReleaseProgressForAnIntermediateSong() {
        val gate = TrackProgressGate()
        gate.updateMedia("First", null)
        gate.acceptLyrics("First", "Artist")
        gate.updateMedia("Second", null)
        gate.updateMedia("Third", null)
        assertFalse(gate.acceptLyrics("Second", "Artist"))
        assertTrue(gate.waitingForLyrics)
        assertTrue(gate.acceptLyrics("Third", "Artist"))
        assertFalse(gate.waitingForLyrics)
    }

    @Test fun absentOrUnrelatedMetadataDoesNotBlockLyricOnlyProviders() {
        val gate = TrackProgressGate()
        gate.updateMedia("Different metadata format", null)
        assertTrue(gate.acceptLyrics("First", "Artist"))
        gate.updateMedia("Other format", null)
        assertFalse(gate.waitingForLyrics)
        assertTrue(gate.acceptLyrics("Second", "Artist"))
        gate.updateMedia(null, null)
        assertFalse(gate.waitingForLyrics)
    }

    @Test fun sameTrackRefreshAndSeekRemainAllowed() {
        val gate = TrackProgressGate()
        gate.updateMedia(" First ", "ARTIST")
        gate.acceptLyrics("First", "Artist")
        assertFalse(gate.waitingForLyrics)
        assertTrue(gate.acceptLyrics("First", "Artist"))
        gate.updateMedia("First", null) // cover/artist-only notification
        assertFalse(gate.waitingForLyrics)
        gate.reset()
        assertTrue(gate.acceptLyrics("Second", "Artist"))
        assertFalse(gate.waitingForLyrics)
    }
}
