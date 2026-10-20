package io.github.proify.lyricon.app.activity.lyric

import android.app.Application
import android.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import android.os.ParcelFileDescriptor
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.compose.ui.geometry.Offset
import androidx.test.platform.app.InstrumentationRegistry
import io.github.proify.lyricon.app.ai.explain.*
import io.github.proify.lyricon.app.compose.theme.AppTheme
import io.github.proify.lyricon.app.util.AppThemeUtils
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import androidx.lifecycle.viewModelScope
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Uses an isolated test APK and a frozen local fixture; never calls a configured paid API. */
class ExplainScreenTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ComponentActivity
    private var closeCount = 0
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private lateinit var state: ExplainViewModel
    private var albumState: AlbumExplainViewModel? = null
    private var originalThemeMode: Int? = null
    private val example get() = ExplainRecord(UUID.randomUUID().toString(),
        ExplainSong("夜航", "林屿", "候鸟来信", listOf(
            ExplainLine("我把晚风，折成一封没有地址的信。", 84000, 91000),
            ExplainLine("灯塔不说话，海却替它等到天明。", 126000, 133000))),
        ExplainRange.LINE, ExplainAngle.PHRASE, 0,
        listOf(ExplainTurn("fixture-root", content = "有些心事，写下就已足够。\n\n这里把摸不着的晚风写成可以折起的信，让无形的思念有了形状。\n\n**没有地址**留下了开放的理解：明知寄不到，仍然想表达。", status = ExplainStatus.COMPLETE)),
        System.currentTimeMillis())

    @Before fun prepare() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val command = "am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10008000 -n ${app.packageName}/androidx.activity.ComponentActivity"
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        compose.waitUntil(5000) {
            var resumed: ComponentActivity? = null
            instrumentation.runOnMainSync { resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<ComponentActivity>().firstOrNull() }
            resumed?.let { activity = it }; resumed != null
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            state = ExplainViewModel(app, ExplainSong(), 0, null)
            state.openHistory(example)
        }
        instrumentation.runOnMainSync { activity.setContent { AppTheme { ExplainScreen(state, onClose = { closeCount++ }) } } }
    }
    @After fun finish() { InstrumentationRegistry.getInstrumentation().runOnMainSync {
        if (::state.isInitialized) state.viewModelScope.cancel()
        albumState?.viewModelScope?.cancel()
        originalThemeMode?.let { AppThemeUtils.setMode(app, it) }
        if (::activity.isInitialized) activity.finish()
    } }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?.let { File(it).apply { mkdirs() } } ?: app.getExternalFilesDir(null)
        File(output, "$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    private fun showAlbumReading(dark: Boolean): AlbumExplainRecord {
        originalThemeMode = AppThemeUtils.getMode(app)
        AppThemeUtils.setMode(app, if (dark) AppThemeUtils.MODE_DARK else AppThemeUtils.MODE_LIGHT)
        val first = AlbumExplainTrack(number = 3, title = "Holy, Holy", lyrics = "测试歌词", status = ExplainStatus.COMPLETE,
            content = "歌曲：3. Holy, Holy\n\n### 歌词与表达\n把夸张的自述与内心的不安联系起来。\n\n### 编曲与律动\n这里以提供的配器笔记说明节奏与人物情绪的联系。\n\n### 在专辑中的位置\n承接前曲的情绪，推动整张专辑的叙述。")
        val second = AlbumExplainTrack(number = 4, title = "The New Sound", instrumental = true, status = ExplainStatus.COMPLETE,
            content = "歌曲 4：The New Sound\n### 编曲与律动\n从音色、节奏与结构阅读这首纯器乐曲。\n### 在专辑中的位置\n让声音承担情绪的推进。")
        val record = AlbumExplainRecord(title = "The New Sound", artist = "Geordie Greep", tracks = listOf(first, second))
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            albumState = AlbumExplainViewModel(app, configProvider = { null })
            albumState!!.openHistory(record)
            activity.setContent { AppTheme { ExplainScreen(state, album = albumState, openAlbum = true, onClose = { closeCount++ }) } }
        }
        compose.onNodeWithTag("album-track-list").performScrollToIndex(1)
        return record
    }

    private fun assertSongBackground(number: Int, index: Int, dark: Boolean) {
        val bitmap = compose.onNodeWithTag("album-track-$number").captureToImage().asAndroidBitmap()
        assertEquals(albumTrackColors(index, dark).background.toArgb(), bitmap.getPixel(4, bitmap.height / 2))
        bitmap.recycle()
    }

    @Test fun albumPastelCardsShowLegacySectionsAndDifferentSongBackgrounds() {
        val record = showAlbumReading(false)
        compose.onNodeWithText("歌曲 3：Holy, Holy").assertIsDisplayed()
        compose.onNodeWithText("歌曲：3. Holy, Holy").assertDoesNotExist()
        compose.onNodeWithText("歌词解读").assertIsDisplayed()
        compose.onNodeWithText("把夸张的自述与内心的不安联系起来。", substring = true).assertExists()
        compose.onAllNodesWithText("编曲解读").onFirst().assertIsDisplayed()
        assertSongBackground(3, 0, false)
        screenshot("ai-album-soft-light")
        compose.onNodeWithTag("album-track-list").performScrollToIndex(2)
        compose.onNodeWithText("歌曲 4：The New Sound").assertIsDisplayed()
        assertSongBackground(4, 1, false)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            albumState!!.openHistory(record.copy(tracks = record.tracks.mapIndexed { index, track ->
                if (index == 0) track.copy(content = "### 编曲与律动\n重新生成的短回答。") else track
            }))
        }
        compose.onNodeWithTag("album-track-list").performScrollToIndex(1)
        compose.onNodeWithText("重新生成的短回答。", substring = true).assertExists()
        compose.onNodeWithText("把夸张的自述与内心的不安联系起来。", substring = true).assertDoesNotExist()
    }

    @Test fun albumPastelCardsAdaptToDarkTheme() {
        showAlbumReading(true)
        compose.onNodeWithText("歌曲 3：Holy, Holy").assertIsDisplayed()
        compose.onNodeWithText("歌词解读").assertIsDisplayed()
        assertSongBackground(3, 0, true)
        screenshot("ai-album-soft-dark")
        compose.onNodeWithTag("album-track-list").performScrollToIndex(2)
        assertSongBackground(4, 1, true)
    }
    @Test fun albumEditorCreatesOrderedCardsAndEditsInstrumentalSource() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            albumState = AlbumExplainViewModel(app, configProvider = { null })
            activity.setContent { AppTheme { ExplainScreen(state, album = albumState, openAlbum = true, onClose = { closeCount++ }) } }
        }
        compose.onNodeWithTag("album-title").performTextInput("The New Sound")
        compose.onNodeWithTag("album-artist").performTextInput("Geordie Greep")
        compose.onNodeWithTag("album-tracks-input").performScrollTo().performTextInput("3.Holy, Holy\n4.The New Sound")
        compose.onNodeWithText("保存曲目").performScrollTo().performClick()
        compose.onNodeWithText("歌曲 3：Holy, Holy").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("资料").onFirst().performClick()
        compose.onNodeWithText("纯器乐曲").assertExists()
        compose.onNode(isToggleable()).performClick()
        compose.onNodeWithText("保存").performClick()
        compose.onNodeWithText("纯器乐").assertExists()
        compose.onNodeWithText("歌曲 4：The New Sound").performScrollTo().assertIsDisplayed()
        screenshot("ai-album-track-cards")
        compose.onNodeWithTag("explain-sheet").performTouchInput { click(Offset(width / 2f, height - 2f)) }
        compose.runOnIdle { assertEquals(0, closeCount); assertEquals(listOf(3, 4), albumState!!.record!!.tracks.map { it.number }) }
    }
    @Test fun bottomPaddingAndInputDoNotDismissTheSheetButOutsideTapDoes() {
        compose.onNodeWithTag("explain-sheet").performTouchInput { click(Offset(width / 2f, height - 2f)) }
        compose.onNodeWithText("继续追问…").performClick()
        compose.runOnIdle { assertEquals(0, closeCount) }
        compose.onNodeWithTag("explain-scrim").performTouchInput { click(Offset(width / 2f, 5f)) }
        compose.runOnIdle { assertEquals(1, closeCount) }
    }

    @Test fun conciseReadingKeepsSourceInformationInMenu() {
        compose.onNodeWithText("AI 解读仅供参考").assertDoesNotExist()
        compose.onNodeWithContentDescription("更多操作").performClick()
        compose.onNodeWithText("解读信息").performClick()
        compose.onNodeWithText("AI 解读仅供参考").assertExists()
        compose.onNodeWithText("知道了").performClick()
        compose.onNodeWithText("继续追问…").assertExists()
    }

    @Test fun albumHistorySearchCombinesWithFavoritesAndSurvivesOpeningAnAlbum() {
        val key = UUID.randomUUID().toString()
        val artist = "筛选艺人-$key"
        val track = AlbumExplainTrack(number = 1, title = "测试曲", content = "本地解读", status = ExplainStatus.COMPLETE)
        val first = AlbumExplainRecord(title = "收藏专辑-$key", artist = artist, tracks = listOf(track), favorite = true)
        val second = AlbumExplainRecord(title = "普通专辑-$key", artist = artist, tracks = listOf(track))
        val store = AlbumExplainHistory(app)
        runBlocking { store.put(first); store.put(second) }
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                albumState = AlbumExplainViewModel(app, configProvider = { null })
                activity.setContent { AppTheme { ExplainScreen(state, album = albumState, openAlbum = true, onClose = { closeCount++ }) } }
            }
            compose.waitUntil(5000) { albumState!!.records.any { it.id == second.id } }
            compose.onNodeWithText("历史").performClick()
            compose.onNodeWithTag("album-history-search").performTextInput(first.title)
            compose.onNode(hasText(first.title) and hasSetTextAction().not()).assertExists()
            compose.onNodeWithText(second.title).assertDoesNotExist()
            compose.onNodeWithTag("album-history-search").performTextReplacement(artist)
            compose.onNodeWithText(first.title).assertExists()
            compose.onNodeWithText(second.title).assertExists()
            compose.onNodeWithText("收藏").performClick()
            compose.onNodeWithText(first.title).assertExists()
            compose.onNodeWithText(second.title).assertDoesNotExist()
            screenshot("ai-album-history-favorites")
            compose.onNodeWithText("打开专辑").performClick()
            compose.onNodeWithText("历史").performClick()
            compose.onNodeWithTag("album-history-search").assertTextContains(artist)
            compose.onNodeWithText(second.title).assertDoesNotExist()
            compose.onNodeWithText("全部").performClick()
            compose.onNodeWithText(second.title).assertExists()
            compose.onNodeWithTag("album-history-search").performTextReplacement("不存在的专辑-$key")
            compose.onNodeWithText("未找到匹配的专辑").assertExists()
        } finally { runBlocking { store.delete(first.id); store.delete(second.id) } }
    }

    @Test fun changingCompletedTrackRequiresConfirmationAndCancelKeepsOutput() {
        val record = showAlbumReading(false)
        compose.onNodeWithTag("album-track-list").performScrollToIndex(1)
        compose.onAllNodesWithText("资料").onFirst().performClick()
        compose.onNode(isToggleable()).performClick()
        compose.onNodeWithText("保存").performClick()
        compose.onNodeWithText("保存后需重新解读本曲").assertExists()
        compose.runOnIdle { assertEquals(record.tracks.first().content, albumState!!.record!!.tracks.first().content) }
        compose.onAllNodesWithText("取消").onLast().performClick()
        compose.runOnIdle { assertEquals(record.tracks.first().content, albumState!!.record!!.tracks.first().content) }
    }

    @Test fun fullScreenBottomPaddingDoesNotCloseOrLoseTheDraft() {
        compose.onNode(hasSetTextAction()).performTextInput("底部点击后保留草稿")
        compose.onNodeWithContentDescription("全屏阅读").performClick()
        compose.onNodeWithTag("explain-sheet").performTouchInput { click(Offset(width / 2f, height - 2f)) }
        compose.onNode(hasSetTextAction()).assertTextContains("底部点击后保留草稿")
        compose.runOnIdle { assertEquals(0, closeCount) }
    }

    @Test fun fullScreenAndReadingSettingsPreserveTheDraftAndAnswer() {
        compose.onNode(hasSetTextAction()).performClick().performTextInput("我想继续了解")
        compose.onNodeWithContentDescription("全屏阅读").performClick()
        compose.onNode(hasSetTextAction()).assertTextContains("我想继续了解")
        screenshot("ai-explain-fullscreen")
        compose.onNodeWithContentDescription("更多操作").performClick()
        compose.onNodeWithText("阅读设置").performClick()
        compose.onNodeWithText("字号 · 15").assertExists()
        screenshot("ai-explain-reading")
        compose.onNodeWithContentDescription("返回解读").performClick()
        compose.onNode(hasSetTextAction()).assertTextContains("我想继续了解")
        assertEquals("fixture-root", state.record.turns.single().id)
    }

    @Test fun lyricPickerAndFavoriteHistoryAreReachable() {
        compose.onNodeWithText("换句").performScrollTo().performClick()
        compose.onNodeWithText("灯塔不说话，海却替它等到天明。").assertExists()
        screenshot("ai-explain-lyrics")
        compose.onNodeWithContentDescription("返回解读").performClick()
        compose.onNodeWithText("收藏").performScrollTo().performClick()
        compose.waitUntil(5000) { state.records.any { it.id == state.record.id && it.favorite } }
        compose.onNodeWithContentDescription("历史与收藏").performClick()
        compose.onNodeWithText("收藏").performClick()
        compose.onNodeWithText("夜航").assertIsDisplayed()
        screenshot("ai-explain-history")
        compose.onNodeWithText("夜航").performClick()
        compose.onNodeWithText("歌曲记录").assertIsDisplayed()
        compose.onAllNodesWithText("已收藏").onFirst().assertExists()
        screenshot("ai-explain-song-history")
    }

    @Test fun shareCardHasEditableExcerptAndCanSaveARealImage() {
        compose.onNodeWithContentDescription("分享解读").performScrollTo().performClick()
        compose.onNodeWithText("摘录").assertExists()
        compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("歌词解读分享卡预览").fetchSemanticsNodes().isNotEmpty() }
        screenshot("ai-explain-share")
        val record = state.record
        val bitmap = ExplainShareCard.render(record.song, record.song.lines[0].text, "测试分享摘录",
            ExplainCardColors(Color.WHITE, Color.BLACK, Color.DKGRAY, Color.BLUE))
        assertTrue(bitmap.height > 500)
        val uri = runBlocking { ExplainShareCard.save(app, bitmap) }
        try { app.contentResolver.openInputStream(uri).use { assertTrue(it!!.readBytes().size > 1000) } }
        finally { app.contentResolver.delete(uri, null, null) }
        val intent = runBlocking { ExplainShareCard.prepareShare(app, bitmap) }
        assertEquals("image/png", intent.type)
        assertTrue(intent.flags and android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val sharedUri = intent.clipData!!.getItemAt(0).uri
        assertEquals("content", sharedUri.scheme)
        app.contentResolver.openInputStream(sharedUri).use { assertTrue(it!!.readBytes().size > 1000) }
        bitmap.recycle()
    }

    @Test fun historyRejectsAStaleWriteOverANewerFavorite() = runBlocking {
        val history = ExplainHistory(app)
        val original = example
        val favorite = original.copy(updatedAt = original.updatedAt + 10, favorite = true)
        history.put(favorite)
        history.put(original)
        assertTrue(history.all().first { it.id == original.id }.favorite)
        val reread = ExplainHistory(app).all().first { it.id == original.id }
        assertEquals(original.song.lines, reread.song.lines)
        assertEquals(original.turns, reread.turns)
    }

    @Test fun groupedHistoryCanDeleteOneVersionOrTheWholeSong() {
        val title = "分组删除验证"
        val first = example.let { it.copy(song = it.song.copy(title = title)) }
        val second = first.copy(id = UUID.randomUUID().toString(), range = ExplainRange.SONG,
            angle = ExplainAngle.OVERVIEW, updatedAt = first.updatedAt + 20)
        compose.runOnIdle { state.openHistory(first); state.toggleFavorite() }
        compose.waitUntil(5000) { state.records.any { it.id == first.id } }
        compose.runOnIdle { state.openHistory(second); state.toggleFavorite() }
        compose.waitUntil(5000) { state.records.any { it.id == second.id } }
        val unrelated = state.records.filter { it.song.title != title }.map { it.id }.toSet()
        compose.onNodeWithContentDescription("历史与收藏").performClick()
        compose.onAllNodesWithText(title).assertCountEquals(1)
        compose.onNodeWithText(title).performClick()
        compose.onNodeWithText("全部版本 · 2 条", substring = true).assertExists()
        compose.onAllNodesWithContentDescription("删除此条解读").onFirst().performClick()
        compose.onNodeWithText("取消").performClick()
        assertEquals(2, state.records.count { it.song.title == title })
        compose.onAllNodesWithContentDescription("删除此条解读").onFirst().performClick()
        screenshot("ai-explain-delete-confirm")
        compose.onNodeWithText("删除").performClick()
        compose.waitUntil(5000) { state.records.count { it.song.title == title } == 1 }
        compose.onNodeWithText("全部版本 · 1 条", substring = true).assertExists()
        compose.onNodeWithText("删除这首歌的全部记录").performClick()
        compose.onNodeWithText("删除").performClick()
        compose.waitUntil(5000) { state.records.none { it.song.title == title } }
        compose.onNodeWithContentDescription("返回歌曲列表").performClick()
        compose.onAllNodesWithText(title).assertCountEquals(0)
        assertTrue(state.records.map { it.id }.containsAll(unrelated))
    }

    @Test fun deletedVersionsCannotBeResurrectedByLateWrites() = runBlocking {
        val history = ExplainHistory(app)
        val original = example
        history.put(original)
        history.delete(setOf(original.id))
        history.put(original.copy(updatedAt = original.updatedAt + 1000))
        assertFalse(ExplainHistory(app).all().any { it.id == original.id })
    }

    @Test fun customQuickQuestionIsSavedDisplayedAndSentAsTheActualFollowUp() {
        val custom = "测试自定义：这句还有哪些理解？"
        compose.onNodeWithContentDescription("更多操作").performClick()
        compose.onNodeWithText("快捷追问").performClick()
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextClearance()
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput(custom)
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(5000) { state.quickPrompts[QuickPromptGroup.LINE]?.firstOrNull() == custom }
        assertEquals(custom, ExplainQuickPrompts(app).get(QuickPromptGroup.LINE).first())
        screenshot("ai-quick-prompts-editor")
        compose.onNodeWithContentDescription("返回解读").performClick()
        compose.onNodeWithText(custom).performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(custom, state.record.turns.last().question) }
    }

    @Test fun quickPromptStorageSeparatesGroupsAndPreservesAnExplicitlyEmptyList() = runBlocking {
        val store = ExplainQuickPrompts(app)
        val original = QuickPromptGroup.entries.associateWith { store.get(it) }
        try {
            store.save(QuickPromptGroup.LINE, listOf("自定义一句"))
            store.save(QuickPromptGroup.SONG, emptyList())
            val reopened = ExplainQuickPrompts(app)
            assertEquals(listOf("自定义一句"), reopened.get(QuickPromptGroup.LINE))
            assertTrue(reopened.get(QuickPromptGroup.SONG).isEmpty())
            assertEquals(original[QuickPromptGroup.LANGUAGE], reopened.get(QuickPromptGroup.LANGUAGE))
            store.reset(QuickPromptGroup.LINE)
            assertEquals(defaultQuickPrompts(QuickPromptGroup.LINE), reopened.get(QuickPromptGroup.LINE))
        } finally { original.forEach { (group, values) -> store.save(group, values) } }
    }
}
