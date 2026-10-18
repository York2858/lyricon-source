package io.github.proify.lyricon.app.activity.lyric

import android.app.Application
import android.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
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
    @Test fun albumEditorCreatesOrderedCardsAndEditsInstrumentalSource() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            albumState = AlbumExplainViewModel(app, configProvider = { null })
            activity.setContent { AppTheme { ExplainScreen(state, album = albumState, openAlbum = true, onClose = { closeCount++ }) } }
        }
        compose.onNodeWithTag("album-title").performTextInput("The New Sound")
        compose.onNodeWithTag("album-artist").performTextInput("Geordie Greep")
        compose.onNodeWithTag("album-tracks-input").performScrollTo().performTextInput("3.Holy, Holy\n4.The New Sound")
        compose.onNodeWithText("应用曲目清单").performScrollTo().performClick()
        compose.onNodeWithText("歌曲：3. Holy, Holy").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("歌词与编曲资料").onFirst().performClick()
        compose.onNodeWithText("纯器乐曲").assertExists()
        compose.onNode(isToggleable()).performClick()
        compose.onNodeWithText("保存资料").performClick()
        compose.onNodeWithText("纯器乐 · 重点解读编曲与结构").assertExists()
        compose.onNodeWithText("歌曲：4. The New Sound").performScrollTo().assertIsDisplayed()
        screenshot("ai-album-track-cards")
        compose.onNodeWithTag("explain-sheet").performTouchInput { click(Offset(width / 2f, height - 2f)) }
        compose.runOnIdle { assertEquals(0, closeCount); assertEquals(listOf(3, 4), albumState!!.record!!.tracks.map { it.number }) }
    }
    @Test fun bottomPaddingAndDisclaimerDoNotDismissTheSheetButOutsideTapDoes() {
        compose.onNodeWithTag("explain-sheet").performTouchInput { click(Offset(width / 2f, height - 2f)) }
        compose.onNodeWithText("AI 解读仅供参考").performClick()
        compose.runOnIdle { assertEquals(0, closeCount) }
        compose.onNodeWithTag("explain-scrim").performTouchInput { click(Offset(width / 2f, 5f)) }
        compose.runOnIdle { assertEquals(1, closeCount) }
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
        compose.onNodeWithText("正文字号 · 15 sp").assertExists()
        screenshot("ai-explain-reading")
        compose.onNodeWithContentDescription("返回解读").performClick()
        compose.onNode(hasSetTextAction()).assertTextContains("我想继续了解")
        assertEquals("fixture-root", state.record.turns.single().id)
    }

    @Test fun lyricPickerAndFavoriteHistoryAreReachable() {
        compose.onNodeWithText("换一句 ›").performScrollTo().performClick()
        compose.onNodeWithText("灯塔不说话，海却替它等到天明。").assertExists()
        screenshot("ai-explain-lyrics")
        compose.onNodeWithContentDescription("返回解读").performClick()
        compose.onNodeWithText("收藏").performScrollTo().performClick()
        compose.waitUntil(5000) { state.records.any { it.id == state.record.id && it.favorite } }
        compose.onNodeWithContentDescription("历史与收藏").performClick()
        compose.onNodeWithText("我的收藏").performClick()
        compose.onNodeWithText("夜航").assertIsDisplayed()
        screenshot("ai-explain-history")
        compose.onNodeWithText("夜航").performClick()
        compose.onNodeWithText("歌曲记录").assertIsDisplayed()
        compose.onAllNodesWithText("已收藏").onFirst().assertExists()
        screenshot("ai-explain-song-history")
    }

    @Test fun shareCardHasEditableExcerptAndCanSaveARealImage() {
        compose.onNodeWithContentDescription("分享解读").performScrollTo().performClick()
        compose.onNodeWithText("解读摘录").assertExists()
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
        compose.onNodeWithText("快捷追问设置").performClick()
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextClearance()
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput(custom)
        compose.onNodeWithText("保存快捷问题").performClick()
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
