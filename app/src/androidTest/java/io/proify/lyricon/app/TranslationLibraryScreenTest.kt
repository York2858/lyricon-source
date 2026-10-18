package io.github.proify.lyricon.app.activity.lyric

import android.app.Application
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.net.Uri
import androidx.compose.ui.unit.dp
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.github.proify.lyricon.app.ai.translation.*
import io.github.proify.lyricon.app.compose.theme.AppTheme
import io.github.proify.lyricon.lyric.ai.translate.*
import kotlinx.coroutines.cancel
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** Local fixtures only. Does not touch real translations or configured AI endpoints. */
class TranslationLibraryScreenTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ComponentActivity
    private lateinit var state: TranslationLibraryViewModel
    private lateinit var repository: FixtureLibrary
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application

    @Before fun setup() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // MIUI blocks ActivityScenario's background launch. Start only the isolated host as shell.
        val command = "am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10008000 -n ${app.packageName}/androidx.activity.ComponentActivity"
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        compose.waitUntil(5000) {
            var resumed: ComponentActivity? = null
            instrumentation.runOnMainSync {
                resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<ComponentActivity>().firstOrNull()
            }
            resumed?.let { activity = it }
            resumed != null
        }
        repository = FixtureLibrary()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { state = TranslationLibraryViewModel(app, repository) }
    }
    @After fun cleanup() { InstrumentationRegistry.getInstrumentation().runOnMainSync {
        if (::state.isInitialized) state.viewModelScope.cancel()
        if (::activity.isInitialized) activity.finish()
    } }
    private fun show() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            activity.setContent { AppTheme { TranslationLibraryScreen(state) {} } }
        }
        compose.waitUntil(5000) { !state.loading }
    }
    private fun screenshot(name: String, popup: Boolean = false) {
        compose.waitForIdle()
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { File(it).apply { mkdirs() } }
            ?: app.getExternalFilesDir(null)
        File(output, "$name.png").outputStream().use {
            val bitmap = if (popup) InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                else compose.onRoot().captureToImage().asAndroidBitmap()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    private fun openReading() {
        compose.onNodeWithText("夜航").performClick()
        compose.waitUntil(5000) { !state.loading }
        compose.onNodeWithText("中文").performClick()
        compose.waitUntil(5000) { !state.loading }
    }

    @Test fun songsSearchAndLanguageSortingAreReachable() {
        show(); screenshot("translation-library-songs")
        compose.onNodeWithText("夜航").assertIsDisplayed()
        compose.onNodeWithText("所有语言").performClick()
        compose.onNodeWithText("中文").performClick()
        compose.waitUntil(5000) { !state.loading }
        assertEquals("中文", repository.lastQuery.language)
        compose.onNode(hasSetTextAction()).performTextInput("sea")
        compose.waitUntil(5000) { !state.loading && repository.lastQuery.search == "sea" }
        compose.onNodeWithText("最近保存").performClick()
        compose.onNodeWithText("最早保存").assertIsDisplayed()
        screenshot("translation-library-sort-menu", popup = true)
        compose.onNodeWithText("歌曲名称").performClick()
        compose.waitUntil(5000) { !state.loading }
        assertEquals("title", repository.lastQuery.sort)
    }

    @Test fun readsAllPagesAndSearchesLastLineWithReadingControls() {
        show(); openReading()
        assertEquals(125, state.lines.size)
        val first = compose.onNodeWithText("original 0").getUnclippedBoundsInRoot()
        val second = compose.onNodeWithText("original 1").getUnclippedBoundsInRoot()
        assertTrue("Lyric rows should be compact", second.top - first.top < 110.dp)
        screenshot("translation-library-reading")
        compose.onNode(hasSetTextAction()).performTextInput("译文 124")
        compose.onNode(hasText("译文 124") and hasSetTextAction().not()).assertIsDisplayed()
        compose.onNodeWithText("只看原文").performClick()
        compose.onNodeWithText("original 124").assertIsDisplayed()
        compose.onNode(hasText("译文 124") and hasSetTextAction().not()).assertDoesNotExist()
        compose.onNodeWithText("字号 +").performScrollTo().performClick()
        screenshot("translation-library-search")
    }

    @Test fun favoriteAndDeleteAreConfirmedBeforeMutation() {
        show(); openReading()
        compose.onNodeWithText("收藏").performClick()
        compose.waitUntil(5000) { state.version?.favorite == true }
        compose.onNodeWithText("删除").performScrollTo().performClick()
        compose.onNodeWithText("删除翻译记录？").assertExists()
        assertEquals(0, repository.deleted)
        compose.onNodeWithText("取消").performClick()
        assertEquals(0, repository.deleted)
        compose.onNodeWithText("删除").performScrollTo().performClick()
        compose.onNodeWithText("确认删除").performClick()
        compose.waitUntil(5000) { repository.deleted == 1 && !state.busy }
        assertNull(state.version)
    }

    @Test fun batchManagementDeletesWholeSongs() {
        show()
        compose.onNodeWithText("批量管理").performClick()
        compose.onNodeWithText("夜航").performClick()
        compose.onNodeWithText("删除").performClick()
        compose.onNodeWithText("确认删除").performClick()
        compose.waitUntil(5000) { repository.deleted == 1 && !state.busy }
        assertTrue(state.selected.isEmpty())
        assertFalse(state.selecting)
    }

    @Test fun errorsOfferRetryAndCopyGetsCompleteRecord() {
        repository.fail = true
        compose.runOnUiThread { state.refresh() }
        show()
        compose.onNodeWithText("数据库暂不可用").assertIsDisplayed()
        compose.onNodeWithText("重试").assertIsDisplayed()
        repository.fail = false
        compose.onNodeWithText("重试").performClick()
        compose.waitUntil(5000) { !state.loading }
        openReading()
        var text = ""
        compose.runOnUiThread { state.copy { text = it } }
        compose.waitUntil(5000) { !state.busy }
        assertTrue(text.contains("original 0\n译文 0"))
        assertTrue(text.contains("original 124\n译文 124"))
    }

    @Test fun exportChooserOffersFiveFormatsAndContentChoices() {
        show(); openReading()
        compose.onNodeWithText("导出歌词").performClick()
        listOf("纯文本 TXT", "逐行 LRC", "逐字 LRC", "增强逐字 LRC", "TTML", "双语", "仅原文", "仅译文", "音乐所在文件夹", "另存为").forEach {
            compose.onNodeWithTag("translation-export-options").performScrollToNode(hasText(it))
            compose.onNodeWithText(it).assertExists()
        }
        compose.onNodeWithTag("translation-export-options").performScrollToNode(hasText("仅译文"))
        compose.onNodeWithText("仅译文").performClick()
        screenshot("translation-library-export", popup = true)
    }

    @Test fun musicFolderWithoutGrantRequestsDirectoryInsteadOfWriting() {
        show(); openReading()
        var requested = false
        compose.runOnUiThread { state.exportToMusicFolder(repository.record, TranslationExportOptions(),
            forceChoose = true, onChooseFolder = { requested = true }) }
        compose.waitUntil(5000) { !state.busy }
        assertTrue(requested)
        assertNull(state.musicOverwrite)
    }

    @Test fun detectedMusicFolderIsDisplayedAndPreselectedForAuthorization() {
        repository.location = TranslationMusicLocation("content://com.android.externalstorage.documents/tree/primary%3AMusic%2FAlbum",
            "Music/Album", "02. 夜航.m4a", "已识别音乐所在文件夹")
        show(); openReading()
        compose.onNodeWithText("导出歌词").performClick()
        compose.waitUntil(5000) { !state.locatingMusic && state.musicLocation != null }
        compose.onNodeWithTag("translation-export-options").performScrollToNode(hasText("授权文件夹"))
        compose.onNodeWithText("已识别：Music/Album\n首次保存需授权此文件夹").assertExists()
        var initial: Uri? = null
        compose.runOnUiThread { state.exportToMusicFolder(repository.record, TranslationExportOptions(),
            onChooseFolder = { initial = it }) }
        compose.waitUntil(5000) { !state.busy }
        assertEquals(repository.location.folderUri, initial.toString())
    }

    @Test fun detectedAudioBasenameIsUsedWhenSavingLyrics() {
        repository.location = TranslationMusicLocation(audioFileName = "02. 夜航.m4a")
        show(); openReading()
        val (folder, uri) = TestMusicDocumentsProvider.createFolder(app)
        try {
            compose.runOnUiThread { state.exportToMusicFolder(repository.record,
                TranslationExportOptions(TranslationExportFormat.LINE), selectedFolder = uri) }
            compose.waitUntil(5000) { !state.busy }
            assertTrue(File(folder, "02. 夜航.lrc").readText().contains("译文 124"))
        } finally { folder.deleteRecursively() }
    }

    @Test fun saveAsWaitsForAudioLookupAndFallsBackOnlyWhenNoFileIsFound() {
        val pending = kotlinx.coroutines.CompletableDeferred<TranslationMusicLocation>()
        repository.locationPending = pending
        show(); openReading()
        var name: String? = null
        compose.runOnUiThread {
            state.locateMusicFolder(repository.record)
            state.prepareSaveAs(repository.record, TranslationExportOptions(TranslationExportFormat.LINE)) { name = it }
        }
        compose.waitForIdle()
        assertTrue(state.busy)
        assertNull(name)
        pending.complete(TranslationMusicLocation(audioFileName = "02. 夜航.m4a"))
        compose.waitUntil(5000) { !state.busy }
        assertEquals("02. 夜航.lrc", name)
        repository.locationPending = null
        compose.runOnUiThread {
            state.locateMusicFolder(repository.record)
            state.prepareSaveAs(repository.record, TranslationExportOptions(TranslationExportFormat.LINE)) { name = it }
        }
        compose.waitUntil(5000) { !state.busy }
        assertEquals("林屿 - 夜航.lrc", name)
    }

    @Test fun musicFolderDuplicateOffersSaveCopyAndKeepsOriginal() {
        show(); openReading()
        val (folder, uri) = TestMusicDocumentsProvider.createFolder(app)
        val options = TranslationExportOptions(TranslationExportFormat.LINE)
        val name = translationExportFileName(repository.record, options)
        try {
            compose.runOnUiThread { state.exportToMusicFolder(repository.record, options, selectedFolder = uri) }
            compose.waitUntil(5000) { !state.busy }
            val original = File(folder, name).readText()
            compose.runOnUiThread { state.exportToMusicFolder(repository.record, options, selectedFolder = uri) }
            compose.waitUntil(5000) { state.musicOverwrite != null && !state.busy }
            compose.onNodeWithText("已存在同名歌词").assertExists()
            compose.onNodeWithText("另存一份").performClick()
            compose.waitUntil(5000) { !state.busy }
            assertEquals(original, File(folder, name).readText())
            assertEquals(original, File(folder, name.substringBeforeLast('.') + " (1).lrc").readText())
        } finally { folder.deleteRecursively() }
    }

    @Test fun exportsAllFormatsToRealFiles() {
        show(); openReading()
        TranslationExportFormat.entries.forEach { format ->
            val file = File(app.cacheDir, "translation-export-test.${format.extension}")
            try {
                compose.runOnUiThread { state.export(Uri.fromFile(file), repository.record, TranslationExportOptions(format)) }
                compose.waitUntil(5000) { !state.busy }
                val text = file.readText()
                assertTrue(text.contains("original")); assertTrue(text.contains("译文 124"))
                if (format == TranslationExportFormat.TTML) assertTrue(text.startsWith("<?xml"))
                if (format == TranslationExportFormat.ENHANCED) assertTrue(text.contains("<00:00.000>"))
            } finally { file.delete() }
        }
    }

    @Test fun batchSelectionOffersFormatsContentAndMusicAuthorization() {
        show()
        compose.onNodeWithText("批量管理").performClick()
        compose.onNodeWithText("全选结果").performClick()
        compose.waitUntil(5000) { !state.busy }
        compose.onNodeWithText("批量导出").performClick()
        compose.onNodeWithText("批量导出歌词").assertExists()
        compose.onNodeWithText("逐行 LRC").performClick()
        compose.onNodeWithText("TTML").performClick()
        compose.onNodeWithText("仅译文").performClick()
        compose.onNodeWithText("打包 ZIP").performClick()
        compose.onNodeWithText("音乐所在文件夹").performClick()
        compose.onNodeWithTag("translation-batch-options").performScrollToNode(hasText("授权音乐文件夹"))
        compose.onNodeWithText("授权音乐文件夹").assertExists()
    }

    @Test fun batchCompletionShowsSummaryAndPerSongResult() {
        show()
        val output = File(app.cacheDir, "batch-screen-test.zip")
        try {
            compose.runOnUiThread { state.exportBatch(listOf(repository.song), BatchExportOptions(), Uri.fromFile(output)) }
            compose.waitUntil(10000) { !state.busy }
            compose.onNodeWithText("批量导出结果").assertExists()
            compose.onNodeWithText("成功 1 · 跳过 0 · 失败 0").assertExists()
            compose.onNodeWithText("完成").performClick()
            assertNull(state.batchResult)
        } finally { output.delete() }
    }

    private class FixtureLibrary : TranslationLibraryRepository {
        var lastQuery = TranslationLibraryQuery(); var fail = false; var deleted = 0
        var location = TranslationMusicLocation()
        var locationPending: kotlinx.coroutines.CompletableDeferred<TranslationMusicLocation>? = null
        override suspend fun musicLocation(id: String) = locationPending?.await() ?: location
        var record = TranslationVersion("v1", "夜航", "林屿", "中文", "fixture", 1720000000000, 125, false, true, true, true)
        val song get() = TranslationSongEntry("song", "夜航", "林屿", 1, 125, if (record.favorite) 1 else 0, record.createdAt, "中文")
        override suspend fun list(query: TranslationLibraryQuery): TranslationLibraryPage {
            if (fail) error("数据库暂不可用")
            lastQuery = query
            return TranslationLibraryPage(if (deleted == 0) listOf(song) else emptyList(), false, if (deleted == 0) 1 else 0, 1, 125, if (record.favorite) 1 else 0, listOf("中文", "日语"))
        }
        override suspend fun versions(group: String, offset: Int) = TranslationVersionPage(if (deleted == 0) listOf(record) else emptyList(), false)
        override suspend fun lines(id: String, offset: Int): TranslationLinePage {
            val end = minOf(offset + 60, 125)
            return TranslationLinePage((offset until end).map {
                val start = it * 2000L
                TranslationLine(it, "original $it", "译文 $it", start, start + 1800,
                    listOf(TranslationWord("original ", start, start + 900), TranslationWord(it.toString(), start + 1000, start + 1800)))
            }, end.takeIf { it < 125 })
        }
        override suspend fun favorite(id: String, value: Boolean) { record = record.copy(favorite = value) }
        override suspend fun delete(ids: List<String>, groups: List<String>): Int { deleted++; return 1 }
    }
}
