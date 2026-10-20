package io.github.proify.lyricon.app.ai.translation

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import io.github.proify.lyricon.lyric.ai.translate.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.zip.ZipFile

class TranslationBatchExportTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var folder: File
    private lateinit var folderUri: Uri
    private lateinit var repository: BatchLibrary
    @Before fun setup() {
        val fixture = TestMusicDocumentsProvider.createFolder(context)
        folder = fixture.first; folderUri = fixture.second; repository = BatchLibrary()
    }
    @After fun cleanup() { folder.deleteRecursively() }
    private fun options(destination: BatchExportDestination, policy: BatchDuplicatePolicy = BatchDuplicatePolicy.SKIP) =
        BatchExportOptions(TranslationExportOptions(TranslationExportFormat.LINE), destination, policy)

    @Test fun unifiedFolderKeepsAudioNamesReadsEveryPageAndCanKeepCopies() = runBlocking {
        val audio = File(folder, "untouched.flac").apply { writeText("original audio") }
        val result = TranslationBatchExporter(context, repository).export(repository.songs,
            options(BatchExportDestination.FOLDER, BatchDuplicatePolicy.COPY), folderUri, { false }, {})
        assertEquals(2, result.succeeded)
        assertTrue(File(folder, "01. 同名.lrc").readText().contains("译文 a 60"))
        assertTrue(File(folder, "01. 同名 (1).lrc").readText().contains("译文 b 60"))
        assertEquals("original audio", audio.readText())
    }
    @Test fun skipPreservesExistingFilesAndReplaceDoesNotOverwriteAnotherSongFromThisBatch() = runBlocking {
        val existing = File(folder, "01. 同名.lrc").apply { writeText("keep existing") }
        val exporter = TranslationBatchExporter(context, repository)
        val skipped = exporter.export(repository.songs, options(BatchExportDestination.FOLDER), folderUri, { false }, {})
        assertEquals(2, skipped.skipped); assertEquals("keep existing", existing.readText())
        val replaced = exporter.export(repository.songs, options(BatchExportDestination.FOLDER, BatchDuplicatePolicy.REPLACE), folderUri, { false }, {})
        assertEquals(2, replaced.succeeded)
        assertTrue(existing.readText().contains("译文 a 60"))
        assertTrue(File(folder, "01. 同名 (1).lrc").readText().contains("译文 b 60"))
    }
    @Test fun zipGroupsSongsKeepsAudioNamesAndReportsBadTimingWithoutStoppingOtherSongs() = runBlocking {
        val output = File(folder, "lyrics.zip")
        val songs = repository.songs + repository.songs.first().copy(key = "bad", title = "缺少时间轴")
        val result = TranslationBatchExporter(context, repository).export(songs,
            options(BatchExportDestination.ZIP), Uri.fromFile(output), { false }, {})
        assertEquals(2, result.succeeded); assertEquals(1, result.failed)
        ZipFile(output).use { zip ->
            assertEquals(3, zip.size())
            val lyrics = zip.getEntry("artist - song a/01. 同名.lrc")
            assertTrue(zip.getInputStream(lyrics).bufferedReader().readText().contains("译文 a 60"))
            val report = zip.getInputStream(zip.getEntry("导出报告.txt")).bufferedReader().readText()
            assertTrue(report.contains("失败")); assertTrue(report.contains("时间轴"))
        }
    }
    @Test fun stoppingBatchKeepsAValidZipAndOnlyAlreadyCompletedSongs() = runBlocking {
        val output = File(folder, "stopped.zip"); var stop = false
        val result = TranslationBatchExporter(context, repository).export(repository.songs,
            options(BatchExportDestination.ZIP), Uri.fromFile(output), { stop }, { if (it.completed == 1) stop = true })
        assertTrue(result.cancelled); assertEquals(1, result.succeeded); assertEquals(2, result.total)
        ZipFile(output).use { zip -> assertEquals(2, zip.size()); assertNotNull(zip.getEntry("导出报告.txt")) }
    }
    @Test fun missingMusicFoldersAreReportedWithoutInventingAnAudioDirectory() = runBlocking {
        val result = TranslationBatchExporter(context, repository).export(repository.songs,
            options(BatchExportDestination.MUSIC), null, { false }, {})
        assertEquals(0, result.succeeded); assertEquals(2, result.failed)
        assertTrue(result.items.all { it.detail.contains("目录") })
        assertTrue(folder.listFiles().orEmpty().isEmpty())
    }
    @Test fun systemFolderPickerUsesADocumentInitialUriAndRejectsNonFolderResults() {
        val tree = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:Music/新歌")
        val intent = TranslationExportPicker.folderIntent(context, tree)
        @Suppress("DEPRECATION")
        val initial = intent.getParcelableExtra<Uri>(DocumentsContract.EXTRA_INITIAL_URI)!!
        assertEquals("primary:Music/新歌", DocumentsContract.getDocumentId(initial))
        assertNotEquals("com.coloros.filemanager", intent.`package`)
        val invalid = Intent().setData(Uri.parse("content://example/document/song"))
        val error = runCatching { TranslationExportPicker.takeFolderGrant(context, Activity.RESULT_OK, invalid) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("文件夹权限"))
    }

    private class BatchLibrary : TranslationLibraryRepository {
        val songs = listOf("a", "b").map { TranslationSongEntry(it, "song $it", "artist", 2, 2, 0, 1, "中文") }
        override suspend fun list(query: TranslationLibraryQuery) = TranslationLibraryPage(songs, false, 2, 4, 4, 0, listOf("中文"))
        override suspend fun versions(group: String, offset: Int, status: String) = TranslationVersionPage(listOf(
            TranslationVersion(group, "song $group", "artist", "中文", "fixture", 2, 2, false, true, true),
            TranslationVersion("old", "old", "artist", "中文", "fixture", 1, 2, false, true, true)), false)
        override suspend fun lines(id: String, offset: Int): TranslationLinePage {
            check(id != "old") { "Must export the latest version" }
            val index = if (offset == 0) 0 else 60
            return TranslationLinePage(listOf(TranslationLine(index, "original $id $index", "译文 $id $index",
                if (id == "bad") null else index * 1000L, if (id == "bad") null else index * 1000L + 900)), if (offset == 0) 60 else null)
        }
        override suspend fun musicLocation(id: String) = TranslationMusicLocation(audioFileName = "01. 同名.flac")
        override suspend fun favorite(id: String, value: Boolean) {}
        override suspend fun delete(ids: List<String>, groups: List<String>, status: String) = 0
    }
}
