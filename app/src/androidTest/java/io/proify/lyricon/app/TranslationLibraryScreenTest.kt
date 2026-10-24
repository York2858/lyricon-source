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
import io.github.proify.lyricon.app.compose.theme.CurrentThemeConfigs
import io.github.proify.lyricon.app.util.AppThemeUtils
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
        instrumentation.runOnMainSync { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        app.getSharedPreferences("translation_export_settings", 0).edit().clear().commit()
        AppThemeUtils.setMode(app, AppThemeUtils.MODE_LIGHT)
        AppThemeUtils.setEnableMonet(app, false)
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
        compose.waitUntil(5000) { !state.loading && state.version != null }
    }
    private fun more(action: String) {
        compose.onNodeWithContentDescription("更多操作").performClick()
        compose.onNodeWithText(action).performClick()
    }
    private fun filters() { compose.onNodeWithTag("translation-filter-action").performClick() }
    private fun choose(current: String, target: String) {
        compose.onAllNodesWithText(current).onLast().performClick()
        compose.onAllNodesWithText(target).onLast().performClick()
    }

    @Test fun timingLabelFollowsRecordCountAndIdentifiesEachVersion() {
        show()
        compose.onNodeWithText("林屿 · 中文").assertIsDisplayed()
        compose.onNodeWithText("逐字").assertIsDisplayed()
        openReading()
        compose.onNodeWithText("⌄", substring = true).performClick()
        compose.onNodeWithText("翻译版本").assertIsDisplayed()
        compose.onNodeWithText(" · 当前", substring = true).assertIsDisplayed()
    }

    @Test fun songsSearchAndLanguageSortingAreReachable() {
        show(); screenshot("translation-library-songs")
        compose.onNodeWithText("夜航").assertIsDisplayed()
        filters(); choose("所有语言", "中文")
        compose.onNodeWithText("应用").performClick()
        compose.waitUntil(5000) { !state.loading }
        assertEquals("中文", repository.lastQuery.language)
        compose.onNode(hasSetTextAction()).performTextInput("sea")
        compose.waitUntil(5000) { !state.loading && repository.lastQuery.search == "sea" }
        compose.onNodeWithText("最近翻译").performClick()
        compose.onNodeWithText("最早翻译").assertIsDisplayed()
        screenshot("translation-library-sort-menu", popup = true)
        compose.onNodeWithText("歌曲名称").performClick()
        compose.waitUntil(5000) { !state.loading }
        assertEquals("title", repository.lastQuery.sort)
    }

    @Test fun allTabContainsOnlySuccessfulTranslationsAndKeepsDiagnosticsInTheirOwnTabs() {
        repository.record = repository.record.copy(status = "error", reason = "连接超时")
        show()
        more("刷新"); compose.waitUntil(5000) { !state.loading }
        assertEquals("translated", repository.lastQuery.status)
        compose.onNodeWithText("夜航").assertDoesNotExist()
        compose.onNodeWithText("报错").performClick()
        compose.waitUntil(5000) { !state.loading && repository.lastQuery.status == "error" }
        compose.onNodeWithText("夜航").assertIsDisplayed()
        compose.onNodeWithText("全部").performClick()
        compose.waitUntil(5000) { !state.loading && repository.lastQuery.status == "translated" }
        compose.onNodeWithText("夜航").assertDoesNotExist()
        repository.record = repository.record.copy(status = "translated")
        more("刷新"); compose.waitUntil(5000) { !state.loading }
        compose.onNodeWithText("夜航").assertIsDisplayed()
        compose.onNodeWithText("连接超时").assertDoesNotExist()
        screenshot("translation-library-success-only")
        compose.onNodeWithText("夜航").performClick(); compose.waitUntil(5000) { !state.loading }
        assertEquals("translated", state.version?.status)
        compose.onNodeWithText("翻译报错").assertDoesNotExist()
    }

    @Test fun skipAndErrorTabsShowSpecificReasonsAndFallbackResults() {
        repository.record = repository.record.copy(status = "skipped", reason = "整首歌词为中文，已按设置跳过",
            suggestion = "需要翻译中文歌时关闭跳过中文")
        show()
        compose.onNodeWithText("跳过").performClick()
        compose.waitUntil(5000) { !state.loading && repository.lastQuery.status == "skipped" }
        compose.onNodeWithText("夜航").performClick(); compose.waitUntil(5000) { !state.loading }
        compose.onNodeWithText("跳过翻译").performClick(); compose.waitUntil(5000) { !state.loading }
        compose.onNodeWithText("整首歌词为中文，已按设置跳过").assertIsDisplayed()
        compose.onNodeWithText("需要翻译中文歌时关闭跳过中文").assertIsDisplayed()
        compose.onNodeWithContentDescription("更多操作").performClick(); compose.onNodeWithText("复制原因").assertIsDisplayed(); compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithText("导出").assertDoesNotExist()
        screenshot("translation-skip-reason")
        compose.onNodeWithContentDescription("返回").performClick(); compose.waitUntil(5000) { !state.loading }
        compose.onNodeWithContentDescription("返回").performClick(); compose.waitUntil(5000) { !state.loading }
        repository.record = repository.record.copy(status = "error", reason = "API Key 无效或已过期（HTTP 401）",
            suggestion = "请到 AI 实验室检查 API Key", resolution = "Google 保底翻译成功，已显示补译结果")
        compose.onNodeWithText("报错").performClick()
        compose.waitUntil(5000) { !state.loading && repository.lastQuery.status == "error" }
        compose.onNodeWithText("夜航").performClick(); compose.waitUntil(5000) { !state.loading }
        compose.onNodeWithText("翻译报错").performClick(); compose.waitUntil(5000) { !state.loading }
        compose.onNodeWithText("API Key 无效或已过期（HTTP 401）").assertIsDisplayed()
        compose.onNodeWithText("Google 保底翻译成功，已显示补译结果").assertIsDisplayed()
        compose.onNodeWithText("请到 AI 实验室检查 API Key").assertIsDisplayed()
        screenshot("translation-error-google-fallback")
    }
    @Test fun waitingRecordRefreshShowsThatLyricsHaveArrived() {
        repository.record = repository.record.copy(status = "skipped", reason = "当时尚未收到歌词，可能正在切歌或匹配")
        show()
        compose.onNodeWithText("跳过").performClick()
        compose.waitUntil(5000) { !state.loading && repository.lastQuery.status == "skipped" }
        compose.onNodeWithText("夜航").performClick(); compose.waitUntil(5000) { !state.loading }
        compose.onNodeWithText("跳过翻译").performClick(); compose.waitUntil(5000) { !state.loading }
        repository.record = repository.record.copy(reason = "切歌或匹配时，歌词曾尚未到达",
            resolution = "已收到 42 行歌词，其中 0 行罗马音、0 行已有翻译")
        more("刷新"); compose.waitUntil(5000) { !state.loading }
        compose.onNodeWithText("已收到歌词").assertIsDisplayed()
        compose.onNodeWithText("后续状态").assertIsDisplayed()
        compose.onNodeWithText("已收到 42 行歌词，其中 0 行罗马音、0 行已有翻译").assertIsDisplayed()
        screenshot("translation-lyrics-arrived")
    }
    @Test fun googlePublicEndpointReturnsAlignedTranslationsWhenLiveCheckIsRequested() = kotlinx.coroutines.runBlocking {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("googleLive") == "true")
        val translated = GoogleLyricsTranslator.translate(listOf("Hello world", "Good night"), "中文")
        assertEquals(2, translated.size)
        assertTrue(translated.all { it.isNotBlank() })
        assertNotEquals("Hello world", translated.first())
        println("GOOGLE_LIVE alignedLines=${translated.size}")
    }

    @Test fun readsAllPagesAndSearchesLastLineWithReadingControls() {
        show(); openReading()
        assertEquals(125, state.lines.size)
        val first = compose.onNodeWithText("original 0").getUnclippedBoundsInRoot()
        val second = compose.onNodeWithText("original 1").getUnclippedBoundsInRoot()
        assertTrue("Lyric rows should leave room for reading", second.top - first.top < 120.dp)
        screenshot("translation-library-reading")
        more("查找歌词")
        compose.onNode(hasSetTextAction()).performTextInput("译文 124")
        compose.onNode(hasText("译文 124") and hasSetTextAction().not()).assertIsDisplayed()
        compose.onNodeWithText("原文").performClick()
        compose.onNodeWithText("original 124").assertIsDisplayed()
        compose.onNode(hasText("译文 124") and hasSetTextAction().not()).assertDoesNotExist()
        more("阅读设置")
        compose.onNodeWithText("字号 +").performClick()
        compose.onNodeWithText("关闭").performClick()
        screenshot("translation-library-search")
    }

    @Test fun favoriteAndDeleteAreConfirmedBeforeMutation() {
        show(); openReading()
        compose.onNodeWithContentDescription("收藏").performClick()
        compose.waitUntil(5000) { state.version?.favorite == true }
        more("删除当前版本")
        compose.onNodeWithText("删除翻译记录？").assertExists()
        assertEquals(0, repository.deleted)
        compose.onNodeWithText("取消").performClick()
        assertEquals(0, repository.deleted)
        more("删除当前版本")
        compose.onNodeWithText("确认删除").performClick()
        compose.waitUntil(5000) { repository.deleted == 1 && !state.busy }
        assertNull(state.version)
    }

    @Test fun batchManagementDeletesWholeSongs() {
        show()
        compose.onNodeWithText("选择").performClick()
        compose.onNodeWithText("夜航").performClick()
        compose.onNodeWithContentDescription("删除选中记录").performClick()
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
        compose.onNodeWithText("导出").performClick()
        compose.onNodeWithText("增强逐字 LRC").performClick()
        TranslationExportFormat.entries.forEach { compose.onAllNodesWithText(it.label).onLast().assertIsDisplayed() }
        compose.onNodeWithText("逐行 LRC").performClick()
        choose("双语", "仅译文")
        choose("音乐所在文件夹", "另存为")
        compose.onNodeWithText("确认导出").assertIsDisplayed()
        screenshot("translation-library-export", popup = true)
    }

    @Test fun exportSelectsFormatBeforeWritingAndFolderHelpIsCollapsed() {
        repository.location = TranslationMusicLocation(audioFileName = "02. 夜航.m4a")
        show(); openReading()
        compose.onNodeWithText("导出").performClick()
        compose.waitUntil(5000) { !state.locatingMusic && state.musicLocation != null }
        choose("增强逐字 LRC", "逐行 LRC")
        compose.onNodeWithTag("translation-export-options").assertExists()
        compose.onNodeWithTag("translation-export-options").performScrollToNode(hasText("02. 夜航.lrc"))
        compose.onNodeWithText("02. 夜航.lrc").assertExists()
        compose.onNodeWithText("请进入 Music 或具体子文件夹。内部存储根目录、Download 根目录可能禁止授权；也可选择另存为。").assertDoesNotExist()
        compose.onNodeWithText("无法授权文件夹？").performScrollTo().performClick()
        compose.onNodeWithText("请进入 Music 或具体子文件夹。内部存储根目录、Download 根目录可能禁止授权；也可选择另存为。").assertExists()
    }

    @Test fun versionDetailsStayAvailableAfterCondensingTheReadingHeader() {
        show(); openReading()
        more("版本信息")
        compose.onNodeWithText("fixture").assertExists()
        compose.onNodeWithText("125 句").assertExists()
        compose.onNodeWithText("知道了").performClick()
        compose.onNodeWithText("original 0").assertExists()
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
        compose.onNodeWithText("导出").performClick()
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
        compose.onNodeWithText("选择").performClick()
        compose.onNodeWithText("全选结果").performClick()
        compose.waitUntil(5000) { !state.busy }
        screenshot("translation-library-selection")
        compose.onNodeWithText("导出 1 首").performClick()
        compose.onNodeWithText("批量导出歌词").assertExists()
        choose("增强逐字 LRC", "TTML")
        choose("双语", "仅译文")
        compose.onNodeWithText("音乐所在文件夹").performClick()
        listOf("音乐所在文件夹", "统一文件夹", "打包 ZIP").forEach { compose.onAllNodesWithText(it).onLast().assertIsDisplayed() }
        compose.onAllNodesWithText("音乐所在文件夹").onLast().performClick()
        compose.onNodeWithTag("translation-batch-options").performScrollToNode(hasText("授权音乐文件夹"))
        compose.onNodeWithText("授权音乐文件夹").assertExists()
        compose.onNodeWithText("开始导出").assertIsDisplayed()
        screenshot("translation-batch-destinations", popup = true)
    }

    @Test fun translationTimeFilterAppliesCancelsAndClearsWithoutLosingOtherFilters() {
        show()
        val date = java.util.Calendar.getInstance().apply {
            timeInMillis = repository.record.createdAt
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }
        val from = date.timeInMillis
        date.add(java.util.Calendar.DAY_OF_MONTH, 1); val before = date.timeInMillis
        compose.runOnUiThread { state.updateQuery(state.query.copy(updatedFrom = from, updatedBefore = before, language = "中文")) }
        compose.waitUntil(5000) { !state.loading }
        filters()
        compose.onNodeWithText("翻译时间范围").assertIsDisplayed()
        screenshot("translation-library-time-dialog", popup = true)
        compose.onNodeWithText("应用").performClick()
        compose.waitUntil(5000) { !state.loading }
        assertEquals(from, repository.lastQuery.updatedFrom); assertEquals(before, repository.lastQuery.updatedBefore)
        assertEquals("中文", repository.lastQuery.language)
        filters(); compose.onNodeWithText("不限开始日期").performClick(); compose.onNodeWithText("取消").performClick()
        assertEquals(from, state.query.updatedFrom)
        filters(); compose.onNodeWithText("清除时间").performClick(); compose.onNodeWithText("应用").performClick()
        compose.waitUntil(5000) { !state.loading }
        assertNull(repository.lastQuery.updatedFrom); assertNull(repository.lastQuery.updatedBefore)
        assertEquals("中文", repository.lastQuery.language)
        screenshot("translation-library-time-filter")
    }

    @Test fun emptyTimeRangeCanBeClearedAndReversedRangeCannotBeApplied() {
        show()
        val time = repository.record.createdAt
        compose.runOnUiThread { state.updateQuery(state.query.copy(updatedFrom = time + 86400000)) }
        compose.waitUntil(5000) { !state.loading }
        compose.onNodeWithText("没有符合条件的歌曲").assertIsDisplayed()
        filters(); compose.onNodeWithText("清除时间").performClick(); compose.onNodeWithText("应用").performClick()
        compose.waitUntil(5000) { !state.loading }
        compose.onNodeWithText("夜航").assertIsDisplayed()
        compose.runOnUiThread { state.updateQuery(state.query.copy(updatedFrom = time + 86400000, updatedBefore = time)) }
        compose.waitUntil(5000) { !state.loading }
        filters()
        compose.onNodeWithText("结束日期不能早于开始日期").assertIsDisplayed()
        compose.onNodeWithText("应用").assertIsNotEnabled()
        compose.onNodeWithText("清除筛选").performClick()
        compose.waitUntil(5000) { !state.loading }
        assertNull(repository.lastQuery.updatedFrom); assertNull(repository.lastQuery.updatedBefore)
    }

    @Test fun datePickersIncludeBothBoundariesOfTheSelectedLocalDay() {
        val date = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.DAY_OF_MONTH, 1); set(java.util.Calendar.HOUR_OF_DAY, 12)
        }
        repository.record = repository.record.copy(createdAt = date.timeInMillis)
        val dayLabel = "${date.get(java.util.Calendar.YEAR)}年${date.get(java.util.Calendar.MONTH) + 1}月1日"
        show(); filters()
        compose.onNodeWithTag("translation-start-date").performClick()
        screenshot("airy-date-picker", popup = true)
        compose.onNode(hasText(dayLabel, substring = true) and hasClickAction() and
            hasAnyAncestor(hasTestTag("translation-date-picker"))).performClick()
        compose.onNodeWithText("确定日期").performClick()
        compose.onNodeWithTag("translation-end-date").performClick()
        compose.onNode(hasText(dayLabel, substring = true) and hasClickAction() and
            hasAnyAncestor(hasTestTag("translation-date-picker"))).performClick()
        compose.onNodeWithText("确定日期").performClick()
        compose.onNodeWithText("应用").performClick()
        compose.waitUntil(5000) { !state.loading }
        date.apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }
        assertEquals(date.timeInMillis, repository.lastQuery.updatedFrom)
        date.add(java.util.Calendar.DAY_OF_MONTH, 1)
        assertEquals(date.timeInMillis, repository.lastQuery.updatedBefore)
        compose.onNodeWithText("夜航").assertIsDisplayed()
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

    @Test fun globalLineCleanupNeedsConfirmationAndDoesNotRequireSelectionOrVisibleResults() {
        repository.deleted = 1
        repository.lineSummary = TranslationLineRecordSummary(123, 4)
        show()
        compose.onNodeWithText("选择").performClick()
        more("删除所有逐行")
        compose.waitUntil(5000) { !state.busy }
        compose.onNodeWithText("删除所有逐行记录？").assertIsDisplayed()
        compose.onNodeWithText("整个翻译库的 123 条", substring = true).assertExists()
        compose.onNodeWithText("4 条已收藏", substring = true).assertExists()
        screenshot("translation-line-cleanup", popup = true)
        assertEquals(0, repository.lineDeletes)
        compose.onAllNodesWithText("取消").onLast().performClick()
        assertNull(state.lineCleanup); assertEquals(0, repository.lineDeletes)
        more("删除所有逐行")
        compose.waitUntil(5000) { !state.busy }
        compose.onNodeWithText("确认删除所有逐行").performClick()
        compose.waitUntil(5000) { !state.busy && !state.loading }
        assertEquals(1, repository.lineDeletes)
        assertNull(state.lineCleanup); assertFalse(state.selecting)
    }

    @Test fun failedLineCleanupKeepsConfirmationAvailableForRetry() {
        repository.lineSummary = TranslationLineRecordSummary(2, 0)
        repository.lineDeleteFails = true
        show()
        compose.onNodeWithText("选择").performClick()
        more("删除所有逐行")
        compose.waitUntil(5000) { !state.busy }
        compose.onNodeWithText("确认删除所有逐行").performClick()
        compose.waitUntil(5000) { !state.busy }
        compose.onNodeWithText("删除所有逐行记录？").assertIsDisplayed()
        assertEquals(0, repository.lineDeletes)
        repository.lineDeleteFails = false
        compose.onNodeWithText("确认删除所有逐行").performClick()
        compose.waitUntil(5000) { !state.busy && !state.loading }
        assertEquals(1, repository.lineDeletes)
        compose.onNodeWithText("夜航").assertIsDisplayed()
    }

    @Test fun dateGroupsAndDirectReaderKeepSelectionAndHistoryReachable() {
        val now = java.time.ZonedDateTime.now()
        AppThemeUtils.setEnableMonet(app, true)
        repository.record = repository.record.copy(createdAt = now.toInstant().toEpochMilli())
        repository.additionalSongs = listOf(repository.song.copy(key = "yesterday", title = "WINDY SUMMER", artist = "杏里", updatedAt = now.minusDays(1).toInstant().toEpochMilli()),
            repository.song.copy(key = "earlier", title = "在水一方", artist = "邓丽君", wordTimedVersions = 0, lineTimedVersions = 1, updatedAt = now.minusDays(5).toInstant().toEpochMilli()))
        compose.runOnUiThread { state.refresh() }
        show()
        compose.onNodeWithText("今天").assertExists()
        screenshot("translation-library-grouped")
        compose.onNodeWithTag("translation-library-list").performScrollToNode(hasText("昨天"))
        compose.onNodeWithText("昨天").assertIsDisplayed()
        compose.onNodeWithTag("translation-library-list").performScrollToNode(hasText("更早"))
        compose.onNodeWithText("更早").assertIsDisplayed()
        compose.onNodeWithTag("translation-library-list").performScrollToNode(hasText("夜航"))
        openReading()
        compose.onNodeWithText("original 0").assertExists()
        compose.onNodeWithText("⌄", substring = true).performClick()
        compose.onNodeWithText("翻译版本").assertExists()
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.waitUntil(5000) { !state.loading && state.group == null }
        compose.onNodeWithText("选择").performClick()
        compose.onNodeWithText("导出 0 首").assertIsNotEnabled()
        compose.onNodeWithTag("translation-library-list").performScrollToNode(hasText("夜航"))
        compose.onNodeWithText("夜航").performClick()
        compose.onNodeWithText("导出 1 首").assertIsEnabled()
    }

    @Test fun monetAndDarkModeUpdateWithoutLosingSearchOrSelection() {
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 31)
        show()
        compose.runOnUiThread {
            state.updateQuery(state.query.copy(search = "夜航")); state.selecting = true
            AppThemeUtils.setMode(app, AppThemeUtils.MODE_DARK)
        }
        compose.waitUntil(5000) { CurrentThemeConfigs.isDark && !state.loading }
        assertEquals("夜航", state.query.search); assertTrue(state.selecting)
        screenshot("translation-library-dark")
        compose.runOnUiThread {
            AppThemeUtils.setEnableMonet(app, true)
            AppThemeUtils.setMode(app, AppThemeUtils.MODE_LIGHT)
        }
        val expected = androidx.compose.material3.dynamicLightColorScheme(app).primary
        compose.waitUntil(5000) { !CurrentThemeConfigs.isDark && CurrentThemeConfigs.primary == expected }
        assertEquals("夜航", state.query.search); assertTrue(state.selecting)
        compose.onNodeWithText("今天").assertDoesNotExist() // historical fixture keeps its date after a theme refresh
        screenshot("translation-library-monet")
        compose.runOnUiThread { AppThemeUtils.setEnableMonet(app, false) }
        compose.waitUntil(5000) { CurrentThemeConfigs.primary == io.github.proify.lyricon.app.compose.theme.appLightColorScheme().primary }
    }

    private class FixtureLibrary : TranslationLibraryRepository {
        var additionalSongs = emptyList<TranslationSongEntry>()
        var lineSummary = TranslationLineRecordSummary(0, 0)
        var lineDeletes = 0
        var lineDeleteFails = false
        override suspend fun lineRecordSummary() = lineSummary
        override suspend fun deleteAllLineRecords(): Int {
            if (lineDeleteFails) error("数据库暂不可用")
            lineDeletes++
            return lineSummary.count.also { lineSummary = TranslationLineRecordSummary(0, 0) }
        }
        var lastQuery = TranslationLibraryQuery(); var fail = false; var deleted = 0
        var location = TranslationMusicLocation()
        var locationPending: kotlinx.coroutines.CompletableDeferred<TranslationMusicLocation>? = null
        override suspend fun musicLocation(id: String) = locationPending?.await() ?: location
        var record = TranslationVersion("v1", "夜航", "林屿", "中文", "fixture", 1720000000000, 125, false, true, true, true)
        val song get() = TranslationSongEntry("song", "夜航", "林屿", 1, 125, if (record.favorite) 1 else 0, record.createdAt, "中文",
            wordTimedVersions = if (record.hasWordTiming) 1 else 0, lineTimedVersions = if (record.hasTiming && !record.hasWordTiming) 1 else 0)
        override suspend fun list(query: TranslationLibraryQuery): TranslationLibraryPage {
            if (fail) error("数据库暂不可用")
            lastQuery = query
            val visible = deleted == 0 && (query.status.isBlank() || query.status == record.status) && (!query.favoritesOnly || record.favorite) &&
                (query.updatedFrom == null || record.createdAt >= query.updatedFrom!!) &&
                (query.updatedBefore == null || record.createdAt < query.updatedBefore!!)
            val rows = if (visible) listOf(song) + additionalSongs else emptyList()
            return TranslationLibraryPage(rows, false, rows.size, 1, 125, if (record.favorite) 1 else 0, listOf("中文", "日语"))
        }
        override suspend fun versions(group: String, offset: Int, status: String) = TranslationVersionPage(if (deleted == 0 && (status.isBlank() || status == record.status)) listOf(record) else emptyList(), false)
        override suspend fun lines(id: String, offset: Int): TranslationLinePage {
            if (record.status != "translated") return TranslationLinePage(emptyList(), null)
            val end = minOf(offset + 60, 125)
            return TranslationLinePage((offset until end).map {
                val start = it * 2000L
                TranslationLine(it, "original $it", "译文 $it", start, start + 1800,
                    listOf(TranslationWord("original ", start, start + 900), TranslationWord(it.toString(), start + 1000, start + 1800)))
            }, end.takeIf { it < 125 })
        }
        override suspend fun favorite(id: String, value: Boolean) { record = record.copy(favorite = value) }
        override suspend fun delete(ids: List<String>, groups: List<String>, status: String): Int { deleted++; return 1 }
    }
}
