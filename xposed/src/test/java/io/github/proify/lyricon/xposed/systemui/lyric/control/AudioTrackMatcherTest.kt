package io.github.proify.lyricon.xposed.systemui.lyric.control

import org.junit.Assert.*
import org.junit.Test

class AudioTrackMatcherTest {
    private val current = AudioTrackIdentity("Uru", "Amanati", "Uru", 281506)
    private val file = LocalAudioTrack(1, "Uru", "Amanati", "Uru", 281507, "audio/mp4")

    @Test fun matchesCurrentAudioDespiteSmallDurationRounding() {
        assertEquals(listOf(file), AudioTrackMatcher.matches(current, listOf(file)))
    }

    @Test fun rejectsOtherArtistsAlbumsAndVersionsWithSameTitle() {
        val others = listOf(file.copy(artist = "Other"), file.copy(album = "Live"),
            file.copy(durationMs = 310000), file.copy(title = "Another song"))
        assertTrue(AudioTrackMatcher.matches(current, others).isEmpty())
    }

    @Test fun preservesAmbiguousMatchesRatherThanChoosingArbitrarily() {
        val duplicates = listOf(file, file.copy(id = 2))
        assertEquals(duplicates, AudioTrackMatcher.matches(current, duplicates))
    }

    @Test fun ignoresCaseAndSurroundingWhitespace() {
        assertEquals(listOf(file), AudioTrackMatcher.matches(
            current.copy(title = " URU ", artist = " amanati ", album = " uru "), listOf(file)))
    }

    @Test fun permitsMissingOptionalMetadataButKeepsKnownIdentityChecks() {
        assertEquals(listOf(file), AudioTrackMatcher.matches(
            current.copy(album = null, durationMs = null), listOf(file)))
        assertTrue(AudioTrackMatcher.matches(current.copy(album = null, durationMs = null),
            listOf(file.copy(artist = "Other"))).isEmpty())
    }
}
