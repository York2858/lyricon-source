package io.github.proify.lyricon.app.ai.translation

import androidx.test.platform.app.InstrumentationRegistry
import io.github.proify.lyricon.lyric.ai.translate.TranslationVersion
import io.github.proify.lyricon.lyric.ai.translate.TranslationMusicLocation
import org.junit.*
import org.junit.Assert.*
import java.io.File
import android.net.Uri
import android.provider.DocumentsContract

class MusicFolderExportTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var folder: File
    private lateinit var uri: Uri
    private lateinit var exporter: MusicFolderExport
    @Before fun setup() {
        val fixture = TestMusicDocumentsProvider.createFolder(context)
        folder = fixture.first; uri = fixture.second
        exporter = MusicFolderExport(context)
    }
    @After fun cleanup() { folder.deleteRecursively() }

    @Test fun writesUtf8LyricsBesideTheAudioFile() {
        val audio = File(folder, "杏里 - WINDY SUMMER.mp3").apply { writeText("original audio") }
        val result = exporter.write(exporter.describe(uri), "杏里 - WINDY SUMMER.lrc", "application/x-lrc", "[00:01.000]夏日的风")
        assertEquals("杏里 - WINDY SUMMER.lrc", (result as MusicFolderWriteResult.Saved).name)
        assertEquals("[00:01.000]夏日的风", File(folder, result.name).readText())
        assertEquals("original audio", audio.readText())
    }
    @Test fun duplicateRequiresConfirmationAndReplaceTruncates() {
        val file = File(folder, "song.lrc").apply { writeText("old lyrics long enough to expose an untruncated tail") }
        val target = exporter.describe(uri)
        assertTrue(exporter.write(target, file.name, "text/plain", "new") is MusicFolderWriteResult.Exists)
        assertTrue(file.readText().startsWith("old lyrics"))
        exporter.write(target, file.name, "text/plain", "new", MusicFileWriteMode.REPLACE)
        assertEquals("new", file.readText())
    }
    @Test fun saveCopyKeepsExistingFilesAndChoosesAvailableName() {
        File(folder, "song.lrc").writeText("first")
        File(folder, "song (1).lrc").writeText("second")
        val result = exporter.write(exporter.describe(uri), "song.lrc", "text/plain", "third", MusicFileWriteMode.COPY)
        assertEquals("song (2).lrc", (result as MusicFolderWriteResult.Saved).name)
        assertEquals("first", File(folder, "song.lrc").readText())
        assertEquals("second", File(folder, "song (1).lrc").readText())
        assertEquals("third", File(folder, result.name).readText())
    }
    @Test fun rememberedFolderIsPerSongAndRequiresPersistentAccess() {
        val key = folder.name
        val version = TranslationVersion("one", key, "artist", "中文", "m", 1, 1, false, true)
        val folderInfo = exporter.describe(uri)
        exporter.remember(version, folderInfo)
        assertNull(exporter.saved(version))
        val granted = MusicFolderExport(context) { true }
        granted.remember(version, folderInfo)
        val reopened = MusicFolderExport(context) { true }
        assertEquals(uri, reopened.saved(version.copy(id = "other-version", language = "日语"))?.uri)
        assertNull(reopened.saved(version.copy(title = "different $key")))
    }
    @Test fun parentGrantCoversOnlyItsChildrenAndTheSameStorageVolume() {
        fun tree(id: String) = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", id)
        val grant = tree("primary:Music")
        assertTrue(exporter.covers(grant, tree("primary:Music/Album")))
        assertTrue(exporter.covers(grant, DocumentsContract.buildDocumentUriUsingTree(grant, "primary:Music/Album")))
        assertFalse(exporter.covers(grant, tree("primary:MusicOther/Album")))
        assertFalse(exporter.covers(grant, tree("AB12-CD34:Music/Album")))
        assertFalse(exporter.covers(grant, tree("primary:Download")))
    }
    @Test fun rememberedOrSelectedParentTargetsTheActualMusicSubfolder() {
        fun tree(id: String) = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", id)
        val parent = MusicFolder(tree("primary:"), "0")
        val location = TranslationMusicLocation(tree("primary:Download/MiShare").toString(), "Download/MiShare", "02. WINDY SUMMER.m4a")
        val actual = exporter.targetWithin(parent, location)
        assertEquals("Download/MiShare", actual.name)
        assertEquals("primary:", DocumentsContract.getTreeDocumentId(actual.uri))
        assertEquals("primary:Download/MiShare", DocumentsContract.getDocumentId(actual.uri))
        val unrelated = MusicFolder(tree("primary:OtherMusic"), "OtherMusic")
        assertEquals(unrelated, exporter.targetWithin(unrelated, location))
        assertEquals(parent, exporter.targetWithin(parent, TranslationMusicLocation()))
    }
}
