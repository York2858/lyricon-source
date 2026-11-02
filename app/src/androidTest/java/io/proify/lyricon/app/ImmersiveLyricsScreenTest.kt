package io.github.proify.lyricon.app.activity.lyric.immersive

import android.app.Application
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.github.proify.lyricon.app.compose.theme.AppTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlinx.coroutines.delay

class ImmersiveLyricsScreenTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ComponentActivity

    @Test fun lyricsOnlyLayoutAndArtistSettings() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val preferences = immersivePreferences(app)
        val previous = preferences.all.toMap()
        try {
            compose.mainClock.autoAdvance = false
            preferences.edit().clear().commit()
            val command = "am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10008000 -n ${app.packageName}/androidx.activity.ComponentActivity"
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
            compose.waitUntil(5000) {
                var resumed: ComponentActivity? = null
                instrumentation.runOnMainSync {
                    resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<ComponentActivity>().firstOrNull()
                }
                resumed?.let { activity = it }
                resumed != null
            }
            lateinit var state: ImmersiveLyricsState
            val liveUpdates = mutableStateOf(false)
            instrumentation.runOnMainSync {
                state = ImmersiveLyricsState(app)
                state.toggleDemo()
                state.seek(2500)
                state.command(io.github.proify.lyricon.app.bridge.ImmersiveLyricsContract.TOGGLE)
                activity.setContent {
                    LaunchedEffect(liveUpdates.value) {
                        if (liveUpdates.value) while (true) {
                            delay(250)
                            state.seek(state.currentPosition() + 1)
                        }
                    }
                    AppTheme { ImmersiveLyricsScreen(state) }
                }
            }
            compose.mainClock.advanceTimeBy(500)
            compose.onNodeWithText("示例预览 · 不播放音频").assertIsDisplayed()
            listOf("返回", "示例", "设置", "上一首", "暂停", "播放", "下一首").forEach {
                compose.onNodeWithText(it).assertDoesNotExist()
            }
            compose.onNodeWithContentDescription("播放进度").assertDoesNotExist()
            compose.runOnIdle { assertFalse(state.playing) }
            screenshot("immersive-preview")
            compose.onNodeWithTag("immersive_lyrics").performTouchInput { swipeUp() }
            compose.mainClock.advanceTimeBy(500)
            compose.runOnIdle { assertEquals(2500L, state.currentPosition()); assertFalse(state.playing); assertEquals(0L, state.followRequest) }
            compose.onNodeWithText("Keep this moment in the song").performScrollTo()
            compose.mainClock.advanceTimeBy(1000)
            compose.onNodeWithText("Keep this moment in the song").assertIsDisplayed().performTouchInput { click() }
            compose.mainClock.advanceTimeBy(100)
            compose.runOnIdle {
                assertTrue(state.playing)
                assertTrue(state.currentPosition() in 31_000L..32_500L)
                assertEquals(1L, state.followRequest)
                state.command(io.github.proify.lyricon.app.bridge.ImmersiveLyricsContract.TOGGLE)
                state.seek(2500)
                preferences.edit().putBoolean("click_seek", false).commit()
            }
            compose.mainClock.advanceTimeBy(100)
            compose.onNodeWithText("Let every word slowly light up").performScrollTo()
            compose.mainClock.advanceTimeBy(1000)
            compose.onNodeWithText("Let every word slowly light up").assertIsDisplayed().performTouchInput { click() }
            compose.mainClock.advanceTimeBy(100)
            compose.runOnIdle {
                assertEquals(2500L, state.currentPosition())
                assertFalse(state.playing)
                assertEquals(1L, state.followRequest)
                preferences.edit().remove("click_seek").commit()
            }
            compose.mainClock.autoAdvance = true
            compose.onNodeWithContentDescription("歌词设置").performClick()
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(1000)
            screenshot("immersive-settings")
            compose.onNodeWithText("歌词显示").assertIsDisplayed()
            compose.onNodeWithTag("immersive_settings").performTouchInput { swipeUp() }
            compose.mainClock.advanceTimeBy(1000)
            compose.runOnIdle { liveUpdates.value = true }
            val toggles = listOf("roma" to true, "pronunciation_below" to false, "translation" to true,
                "word_lift" to true, "keep_screen_on" to false, "rainbow" to false, "hdr" to false,
                "sustain_motion" to true, "perspective" to false, "backing_vocals" to true,
                "pause_current_only" to true, "click_seek" to true, "word_seek" to false, "touch_feedback" to false)
            toggles.forEach { (key, default) ->
                val tag = "lyric_setting_$key"
                compose.onNodeWithTag(tag).performScrollTo()
                compose.mainClock.advanceTimeBy(500)
                compose.onNodeWithTag(tag).performTouchInput { click(center.copy(x = width * .25f)) }
                compose.mainClock.advanceTimeBy(300)
                compose.runOnIdle { assertEquals("row tap: $key", !default, preferences.getBoolean(key, default)) }
                compose.onNodeWithTag(tag).performTouchInput { click(center.copy(x = width * .9f)) }
                compose.mainClock.advanceTimeBy(300)
                compose.runOnIdle { assertEquals("switch tap: $key", default, preferences.getBoolean(key, !default)) }
            }
            compose.runOnIdle { liveUpdates.value = false }
            compose.onNodeWithText("歌词样式").performScrollTo().performTouchInput { click() }
            compose.onNodeWithTag("lyric_slider_primary_size").performScrollTo().performTouchInput {
                click(androidx.compose.ui.geometry.Offset(width * .8f, height * .75f))
            }
            compose.mainClock.advanceTimeBy(300)
            compose.runOnIdle { assertTrue("font size slider saves a changed value", preferences.getInt("primary_size", 32) > 32) }
            compose.onNodeWithText("返回歌词显示").performScrollTo().performTouchInput { click() }
            compose.mainClock.advanceTimeBy(300)
            compose.onNode(hasText("显示翻译")).performScrollTo()
            compose.onNodeWithText("显示翻译").performTouchInput { click() }
            compose.mainClock.advanceTimeBy(100)
            compose.runOnIdle { assertFalse(preferences.getBoolean("translation", true)) }
            compose.onNodeWithText("歌词背景").performScrollTo().performClick()
            compose.onNodeWithText("背景样式").performClick()
            compose.onNode(hasText("Apple Music 封面流光") and hasAnyAncestor(isDialog()), useUnmergedTree = true).performClick()
            compose.onNodeWithTag("lyric_setting_flow_enabled").performScrollTo().performTouchInput { click(center) }
            compose.mainClock.advanceTimeBy(300)
            compose.runOnIdle { assertFalse(preferences.getBoolean("flow_enabled", true)) }
            compose.onNodeWithText("背景样式").performClick()
            compose.onNodeWithText("Beautiful Lyrics", useUnmergedTree = true).performClick()
            compose.onNodeWithText("Beautiful Lyrics 亮度").assertExists()
            compose.runOnIdle { assertEquals(1, preferences.getInt("background_mode", 0)) }
            screenshot("immersive-beautiful-settings")
            compose.onNodeWithText("背景样式").performClick()
            compose.onNode(hasText("Apple Music 封面流光") and hasAnyAncestor(isDialog()), useUnmergedTree = true).performClick()
            compose.onNodeWithText("Apple Music 流光模糊").assertExists()
            compose.onNodeWithText("返回歌词显示").performClick()
            compose.onNodeWithText("歌词字体").performScrollTo().performClick()
            compose.onNodeWithText("原文 字体").performClick()
            compose.onNodeWithText("系统字体", useUnmergedTree = true).performClick()
            compose.runOnIdle { assertEquals("system", preferences.getString("original_font", null)) }
            compose.onNodeWithText("返回歌词").performClick()
            instrumentation.runOnMainSync { state.toggleDemo() }
            compose.runOnIdle { assertFalse(state.demo); assertNull(state.song) }
        } finally {
            preferences.edit().clear().apply {
                previous.forEach { (key, value) ->
                    when (value) {
                        is Boolean -> putBoolean(key, value)
                        is Float -> putFloat(key, value)
                        is Int -> putInt(key, value)
                        is Long -> putLong(key, value)
                        is String -> putString(key, value)
                    }
                }
            }.commit()
        }
    }

    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    @After fun close() {
        if (::activity.isInitialized) InstrumentationRegistry.getInstrumentation().runOnMainSync { activity.finish() }
    }
}
