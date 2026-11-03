package io.github.proify.lyricon.app.activity.lyric.immersive

import android.app.Application
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.github.proify.lyricon.app.ai.explain.explainSongFromBundle
import io.github.proify.lyricon.lyric.ai.explain.AiExplainContract
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class SongHeaderTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ComponentActivity
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Before fun prepare() {
        val command = "am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10008000 -n ${instrumentation.targetContext.packageName}/androidx.activity.ComponentActivity"
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        compose.waitUntil(5000) {
            instrumentation.runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<ComponentActivity>().firstOrNull()?.let { activity = it }
            }
            ::activity.isInitialized
        }
    }

    @After fun finish() {
        if (::activity.isInitialized) instrumentation.runOnMainSync { activity.finish() }
    }

    @Test fun bothLinesLoopToBeginningThenWaitFiveSecondsAfterLongest() {
        compose.mainClock.autoAdvance = false
        val title = mutableStateOf("A very long song title that keeps moving beyond the artist name")
        val artist = mutableStateOf("A moderately long artist name with several guest performers")
        instrumentation.runOnMainSync {
            activity.setContent {
                SynchronizedSongHeader(title.value, artist.value, FontFamily.Default, Color.White, true, Modifier.width(180.dp))
            }
        }
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
        val titleNode = compose.onNodeWithTag("song_title_marquee")
        val artistNode = compose.onNodeWithTag("song_artist_marquee")
        fun x(node: SemanticsNodeInteraction) = node.fetchSemanticsNode().positionInRoot.x
        val startTitle = x(titleNode)
        val startArtist = x(artistNode)
        compose.mainClock.advanceTimeBy(1000)
        val titleTravel = startTitle - x(titleNode)
        val artistTravel = startArtist - x(artistNode)
        assertTrue("title actually scrolls: title=$titleTravel artist=$artistTravel width=${titleNode.fetchSemanticsNode().size.width}", titleTravel > 10f)
        assertEquals("same speed and start", titleTravel, artistTravel, 1f)
        val density = instrumentation.targetContext.resources.displayMetrics.density
        val titleWidth = titleNode.fetchSemanticsNode().size.width.toFloat()
        val artistWidth = artistNode.fetchSemanticsNode().size.width.toFloat()
        val artistFinishAt = songMarqueeDuration(artistWidth + 32f * density, 30f * density).toLong()
        val finishAt = songMarqueeDuration(titleWidth + 32f * density, 30f * density).toLong()
        compose.mainClock.advanceTimeBy((artistFinishAt - 1064 + 128).coerceAtLeast(1))
        assertEquals("shorter line returns to its full beginning", startArtist, x(artistNode), 4f)
        assertTrue("longer line keeps looping", x(titleNode) < startTitle - 10f)
        compose.mainClock.advanceTimeBy(finishAt - artistFinishAt)
        val titleEnd = x(titleNode)
        val artistEnd = x(artistNode)
        assertEquals("title pauses at the beginning", startTitle, titleEnd, 4f)
        assertEquals("artist pauses at the beginning", startArtist, artistEnd, 4f)
        compose.mainClock.advanceTimeBy(4500)
        assertEquals("title pauses at end", titleEnd, x(titleNode), 1f)
        assertEquals("artist waits for title", artistEnd, x(artistNode), 1f)
        compose.mainClock.advanceTimeBy(600)
        assertTrue("next shared cycle starts", x(titleNode) < titleEnd - 10f)
        assertEquals(x(titleNode), x(artistNode), 1f)
        compose.runOnUiThread { title.value = "Short"; artist.value = "Artist" }
        compose.mainClock.advanceTimeBy(64)
        val shortTitle = x(titleNode)
        val shortArtist = x(artistNode)
        compose.mainClock.advanceTimeBy(10000)
        assertEquals(shortTitle, x(titleNode), .1f)
        assertEquals(shortArtist, x(artistNode), .1f)
    }

    @Test fun headerFontSizesUpdateWithoutRestarting() {
        val titleSize = mutableStateOf(22)
        val artistSize = mutableStateOf(14)
        instrumentation.runOnMainSync {
            activity.setContent {
                SynchronizedSongHeader("Song", "Artist", FontFamily.Default, Color.White, true,
                    Modifier.width(250.dp), titleSize.value, artistSize.value)
            }
        }
        val title = compose.onNodeWithTag("song_title_marquee")
        val artist = compose.onNodeWithTag("song_artist_marquee")
        val titleHeight = title.fetchSemanticsNode().size.height
        val artistHeight = artist.fetchSemanticsNode().size.height
        compose.runOnUiThread { titleSize.value = 32; artistSize.value = 22 }
        compose.waitForIdle()
        assertTrue(title.fetchSemanticsNode().size.height > titleHeight)
        assertTrue(artist.fetchSemanticsNode().size.height > artistHeight)
    }

    @Test fun titleOpensEditorWhileArtistRemainsNoninteractive() {
        var opened = 0
        instrumentation.runOnMainSync {
            activity.setContent {
                SynchronizedSongHeader("Song title", "Artist", FontFamily.Default, Color.White, true,
                    Modifier.width(250.dp), onTitleClick = { opened++ })
            }
        }
        compose.onNodeWithText("Song title").performClick()
        compose.runOnIdle { assertEquals(1, opened) }
        compose.onNodeWithText("Artist").assertHasNoClickAction()
    }

    @Test fun songVersionDialogUsesLyricFontAndReturnsChosenFile() {
        val choices = listOf(SongVersionChoice("original", "Blues", "Artist · Original album · 3:20 · Music/Original/Blues.flac"),
            SongVersionChoice("remaster", "Blues", "Artist · Remaster · 3:22 · Music/Remaster/Blues.flac"))
        var selected: SongVersionChoice? = null
        var dismissed = false
        instrumentation.runOnMainSync {
            activity.setContent {
                SongVersionChooser(choices, null,
                    io.github.proify.lyricon.app.activity.lyric.immersive.halcyon.PlayerPalette.Default,
                    mapOf("background_mode" to 3), FontFamily.Monospace, false, 0, false,
                    onDismiss = { dismissed = true }, onSelect = { selected = it })
            }
        }
        compose.onNodeWithTag("song_version_chooser").assertIsDisplayed()
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText("选择歌曲版本").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(FontFamily.Monospace, layouts.single().layoutInput.style.fontFamily)
        compose.onNodeWithText(choices[1].detail).performClick()
        compose.runOnIdle { assertEquals(choices[1], selected) }
        val bitmap = compose.onNodeWithTag("song_version_chooser").captureToImage()
        val native = bitmap.asAndroidBitmap()
        java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "song-version-dialog.png")
            .outputStream().use { native.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertTrue(dismissed) }
    }

    @Test fun fullscreenSettingHidesAndRestoresOnlyStatusBar() {
        val app = instrumentation.targetContext.applicationContext as Application
        val preferences = immersivePreferences(app)
        val previous = preferences.all["fullscreen"] as? Boolean
        try {
            compose.mainClock.autoAdvance = false
            instrumentation.runOnMainSync {
                preferences.edit().putBoolean("fullscreen", false).commit()
                val playback = ImmersiveLyricsState(app)
                activity.setContent { io.github.proify.lyricon.app.compose.theme.AppTheme { ImmersiveLyricsScreen(playback) } }
            }
            compose.mainClock.advanceTimeBy(100)
            fun statusVisible(): Boolean {
                var visible = true
                instrumentation.runOnMainSync {
                    visible = androidx.core.view.ViewCompat.getRootWindowInsets(activity.window.decorView)
                        ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.statusBars()) ?: true
                }
                return visible
            }
            compose.waitUntil(5000) { statusVisible() }
            instrumentation.runOnMainSync { preferences.edit().putBoolean("fullscreen", true).commit() }
            compose.mainClock.advanceTimeBy(100)
            compose.waitUntil(5000) { !statusVisible() }
            instrumentation.runOnMainSync { preferences.edit().putBoolean("fullscreen", false).commit() }
            compose.mainClock.advanceTimeBy(100)
            compose.waitUntil(5000) { statusVisible() }
        } finally {
            instrumentation.runOnMainSync {
                preferences.edit().apply { if (previous == null) remove("fullscreen") else putBoolean("fullscreen", previous) }.commit()
            }
        }
    }

    @Test fun coverEntryTransfersTimedLyricsAndCurrentPosition() {
        instrumentation.runOnMainSync {
            val state = ImmersiveLyricsState(instrumentation.targetContext.applicationContext as Application)
            state.toggleDemo()
            state.command(io.github.proify.lyricon.app.bridge.ImmersiveLyricsContract.TOGGLE)
            state.seek(9000)
            val intent = state.explanationIntent(activity)
            assertTrue(intent.getBooleanExtra(io.github.proify.lyricon.app.activity.lyric.AiExplainActivity.EXTRA_FROM_IMMERSIVE, false))
            val extras = intent.extras!!
            val song = explainSongFromBundle(extras)
            assertEquals(state.title, song.title)
            assertEquals(state.artist, song.artist)
            assertEquals(state.song!!.lyrics.orEmpty().size, song.lines.size)
            assertEquals(state.song!!.lyrics.orEmpty()[1].begin, song.lines[1].start)
            assertEquals(9000L, extras.getLong(AiExplainContract.EXTRA_POSITION))
        }
    }

    @Test fun tappingCoverOpensExplanationAndBackReturnsToLyrics() {
        instrumentation.runOnMainSync {
            val playback = ImmersiveLyricsState(instrumentation.targetContext.applicationContext as Application)
            activity.setContent {
                io.github.proify.lyricon.app.compose.theme.AppTheme { ImmersiveLyricsScreen(playback) }
            }
        }
        compose.onNodeWithContentDescription("打开音乐解读").performClick()
        compose.waitUntil(5000) {
            var opened = false
            instrumentation.runOnMainSync {
                opened = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .any { it is io.github.proify.lyricon.app.activity.lyric.AiExplainActivity }
            }
            opened
        }
        compose.onNodeWithText("音乐解读").assertIsDisplayed()
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithContentDescription("打开音乐解读").assertIsDisplayed()
    }
}
