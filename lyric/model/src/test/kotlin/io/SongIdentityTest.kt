package io

import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.lyric.model.isSameTrackAs
import org.junit.Assert.*
import org.junit.Test

class SongIdentityTest {
    @Test fun addingAiTranslationKeepsTheSameTrack() {
        val original = Song(name = "Song", artist = "Artist", lyrics = listOf(RichLyricLine(text = "Hello")))
        val translated = original.copy(lyrics = listOf(RichLyricLine(text = "Hello", translation = "你好")))
        assertTrue(original.isSameTrackAs(translated))
    }

    @Test fun changingTrackIdIsANewTrackEvenIfTitlesMatch() {
        assertFalse(Song(id = "one", name = "Song").isSameTrackAs(Song(id = "two", name = "Song")))
    }

    @Test fun displayMetadataChangesDoNotResetAStableTrackId() {
        assertTrue(Song(id = "one", name = "Old display").isSameTrackAs(Song(id = "one", name = "New display")))
    }

    @Test fun differentTitlesOrArtistsAreDifferentTracksWithoutIds() {
        val song = Song(name = "First", artist = "Artist")
        assertFalse(song.isSameTrackAs(Song(name = "Second", artist = "Artist")))
        assertFalse(song.isSameTrackAs(Song(name = "First", artist = "Other artist")))
    }

    @Test fun unknownTracksAreNotMistakenForThePreviousTrack() {
        assertFalse(Song().isSameTrackAs(Song()))
        assertFalse(Song(name = "Song").isSameTrackAs(null))
    }
}
