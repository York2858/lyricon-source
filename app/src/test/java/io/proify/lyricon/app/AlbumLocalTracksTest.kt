package io.proify.lyricon.app

import io.github.proify.lyricon.app.ai.explain.*
import org.junit.Assert.*
import org.junit.Test

class AlbumLocalTracksTest {
    private fun file(title: String = "Holy, Holy", order: Int = 3, name: String = "03. Holy, Holy.flac",
        artist: String = "Geordie Greep", path: String = "Music/Album/", album: Long = 1) =
        LocalAlbumFile(album, title, artist, order, name, path, "external_primary")

    @Test fun mergesFormatsDirectoriesAndDuplicateMediaRowsButKeepsLyricCandidates() {
        val original = file()
        val mp3 = file(name = "03. Holy, Holy.mp3", path = "Music/Copy/", album = 2)
        val groups = groupLocalAlbumFiles(listOf(original, mp3, original))
        assertEquals(1, groups.size)
        assertEquals(3, groups.single().size)
        assertTrue(groups.single().contains(mp3))
    }

    @Test fun ignoresTagCaseWhitespaceAndFullWidthCharacters() {
        assertEquals(1, groupLocalAlbumFiles(listOf(file(title = "  Ｈｏｌｙ,   Holy "),
            file(title = "holy, holy", artist = " GEORDIE  GREEP "))).size)
    }

    @Test fun preservesDifferentDiscsRepeatedPositionsArtistsAndExplicitVersions() {
        val files = listOf(file(), file(order = 8), file(order = 2003), file(artist = "Another artist"), file(title = "Holy, Holy (Live)"))
        assertEquals(5, groupLocalAlbumFiles(files).size)
        assertEquals(listOf(1, 1, 1, 1, 2), groupLocalAlbumFiles(files).map { it.first().disc })
    }

    @Test fun mergesMissingTrackTagOnlyWhenTheKnownPositionIsUnambiguous() {
        val unknown = file(order = 0, name = "Holy, Holy.mp3")
        assertEquals(1, groupLocalAlbumFiles(listOf(file(), unknown)).size)
        assertEquals(3, groupLocalAlbumFiles(listOf(file(), file(order = 8), unknown)).size)
    }

    @Test fun usesFileTrackNumberAndSortsAfterRemovingDuplicates() {
        val groups = groupLocalAlbumFiles(listOf(file(title = "Finale", order = 10),
            file(), file(order = 0, name = "03. Holy, Holy.mp3"), file(title = "Intro", order = 1)))
        assertEquals(listOf(1, 3, 10), groups.map { it.first().number })
    }

    @Test fun equivalentAlbumIdsBecomeOneCandidateButDifferentEditionsStaySeparate() {
        val original = listOf(file(), file(title = "The New Sound", order = 4))
        val copy = original.reversed().map { it.copy(albumId = 2, relativePath = "Music/Copy/", fileName = it.fileName.replace("flac", "mp3")) }
        assertEquals(1, groupLocalAlbumCopies(original + copy).size)
        assertEquals(2, groupLocalAlbumFiles(groupLocalAlbumCopies(original + copy).single()).size)
        assertEquals(2, groupLocalAlbumCopies(original + copy + file(title = "Bonus", order = 5, album = 2)).size)
    }
}
