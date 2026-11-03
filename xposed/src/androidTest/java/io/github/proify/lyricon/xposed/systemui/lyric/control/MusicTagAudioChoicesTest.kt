package io.github.proify.lyricon.xposed.systemui.lyric.control

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.ContextWrapper
import android.content.pm.ProviderInfo
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class MusicTagAudioChoicesTest {
    private val track = AudioTrackIdentity("Blues", "Fixture artist", null, null)

    private fun fixture(block: (ContextWrapper, FixtureProvider) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val audio = File.createTempFile("song-versions-", ".flac", base.cacheDir).apply { writeText("audio fixture") }
        val provider = FixtureProvider(audio)
        provider.attachInfo(base, ProviderInfo().apply { authority = "media"; exported = true; applicationInfo = base.applicationInfo })
        val resolver = ContentResolver.wrap(provider)
        val context = object : ContextWrapper(base) { override fun getContentResolver() = resolver }
        try { block(context, provider) } finally { audio.delete() }
    }

    @Test fun duplicateSongsReturnDistinctFilesWithAlbumDurationAndPath() = fixture { context, _ ->
        val choices = MusicTagLauncher.resolveAudioChoices(context, null, track)
        assertEquals(listOf("1", "2"), choices.map { it.uri.lastPathSegment })
        assertTrue(choices.all { it.title == "Blues" })
        assertTrue(choices[0].detail.contains("Original · 3:20 · Music/Original/Blues.flac"))
        assertTrue(choices[1].detail.contains("Remaster · 3:22 · Music/Remaster/Blues.flac"))
    }

    @Test fun knownSourceOpensItsFileWithoutSearchingSameTitledVersions() = fixture { context, provider ->
        val choices = MusicTagLauncher.resolveAudioChoices(context, "content://media/external/audio/media/2", track)
        assertEquals("2", choices.single().uri.lastPathSegment)
        assertEquals(0, provider.queries)
    }

    @Test fun knownAlbumAndDurationKeepOnlyMatchingVersion() = fixture { context, _ ->
        val choices = MusicTagLauncher.resolveAudioChoices(context, null, track.copy(album = "Remaster", durationMs = 202000))
        assertEquals("2", choices.single().uri.lastPathSegment)
    }

    @Test fun unreadableFilesAreExcludedAndRemoteAudioDoesNotUseLocalCopies() = fixture { context, provider ->
        provider.unreadable = "2"
        assertEquals("1", MusicTagLauncher.resolveAudioChoices(context, null, track).single().uri.lastPathSegment)
        val before = provider.queries
        assertTrue(MusicTagLauncher.resolveAudioChoices(context, "https://example.com/Blues.flac", track).isEmpty())
        assertEquals(before, provider.queries)
    }

    private class FixtureProvider(private val audio: File) : ContentProvider() {
        var queries = 0
        var unreadable = ""
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?) =
            MatrixCursor(requireNotNull(projection)).apply {
                queries++
                addRow(arrayOf<Any>(1L, "Blues", "Fixture artist", "Original", 200000L, "audio/flac", "Blues.flac", "Music/Original/"))
                addRow(arrayOf<Any>(2L, "Blues", "Fixture artist", "Remaster", 202000L, "audio/flac", "Blues.flac", "Music/Remaster/"))
                addRow(arrayOf<Any>(3L, "Blues", "Other artist", "Other album", 200000L, "audio/flac", "Blues.flac", "Music/Other/"))
            }
        override fun getType(uri: Uri) = "audio/flac"
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            if (uri.lastPathSegment == unreadable) throw java.io.FileNotFoundException()
            return ParcelFileDescriptor.open(audio, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("read only")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = error("read only")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = error("read only")
    }
}
