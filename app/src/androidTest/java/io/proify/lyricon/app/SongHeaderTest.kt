package io.github.proify.lyricon.app.activity.lyric.immersive

import android.app.Application
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

    @Test fun bothLinesMoveTogetherThenWaitFiveSecondsAfterLongest() {
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
        val viewport = compose.onNodeWithTag("song_header").fetchSemanticsNode().size.width.toFloat()
        val finishAt = songMarqueeDuration(titleWidth - viewport, 30f * density).toLong()
        compose.mainClock.advanceTimeBy((finishAt - 1064 + 128).coerceAtLeast(1))
        val titleEnd = x(titleNode)
        val artistEnd = x(artistNode)
        assertTrue("shorter line stops earlier", artistEnd > titleEnd)
        compose.mainClock.advanceTimeBy(4500)
        assertEquals("title pauses at end", titleEnd, x(titleNode), 1f)
        assertEquals("artist waits for title", artistEnd, x(artistNode), 1f)
        compose.mainClock.advanceTimeBy(600)
        assertTrue("next shared cycle starts", x(titleNode) > titleEnd + 10f)
        assertEquals(x(titleNode), x(artistNode), 1f)
        compose.runOnUiThread { title.value = "Short"; artist.value = "Artist" }
        compose.mainClock.advanceTimeBy(64)
        val shortTitle = x(titleNode)
        val shortArtist = x(artistNode)
        compose.mainClock.advanceTimeBy(10000)
        assertEquals(shortTitle, x(titleNode), .1f)
        assertEquals(shortArtist, x(artistNode), .1f)
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
