package io.github.proify.lyricon.xposed.systemui.lyric

import android.graphics.PixelFormat
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.lyric.style.LyricStyle
import io.github.proify.lyricon.lyric.style.TextStyle
import io.github.proify.lyricon.lyric.view.RichLyricLineView
import io.github.proify.lyricon.statusbarlyric.SuperText
import io.github.proify.lyricon.statusbarlyric.StatusBarLyric
import io.github.proify.lyricon.lyric.view.LyricViewStyle
import io.github.proify.lyricon.lyric.view.AuxiliaryLineLayout
import io.github.proify.lyricon.lyric.view.TextLook
import io.github.proify.lyricon.lyric.view.Highlight
import io.github.proify.lyricon.lyric.view.WordMotion
import io.github.proify.lyricon.xposed.systemui.lyric.control.MediaPanelTestActivity
import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exercises the actual view/style path in an isolated APK without touching player settings. */
class LyricAuxiliaryDisplayTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val source = RichLyricLine(begin = 0, end = 8000, text = "原歌词", roma = "romaji", translation = "翻译")
    private fun line(view: SuperText) = view.getChildAt(0) as RichLyricLineView

    @Test fun hiddenLyricsWaitForTheFirstLineAndResumeAtTheLatestPosition() {
        ActivityScenario.launch(MediaPanelTestActivity::class.java).use { scenario ->
            lateinit var lyric: StatusBarLyric
            scenario.onActivity { activity ->
                val style = LyricStyle().apply {
                    basicStyle.hideOnLockScreen = false
                    packageStyle.text.placeholderFormat = TextStyle.PlaceholderFormat.NONE
                    packageStyle.text.enableEnterAnim = false
                    packageStyle.anim.enable = false
                }
                lyric = StatusBarLyric(activity, style, null).apply {
                    setSong(Song(lyrics = listOf(
                        RichLyricLine(begin = 1000, end = 1999, text = "first"),
                        RichLyricLine(begin = 2000, end = 4000, text = "second")
                    )))
                    setPlaying(true)
                }
                activity.setContentView(FrameLayout(activity).apply { addView(lyric) })
            }
            scenario.onActivity {
                lyric.setPosition(500)
                assertEquals(View.GONE, lyric.visibility)
                assertEquals(0, lyric.textView.childCount)

                lyric.setPosition(1000)
                assertEquals(View.VISIBLE, lyric.visibility)
                assertEquals("first", line(lyric.textView).main.model.text)

                lyric.isDisabledVisible = true
                lyric.setPosition(2500)
                assertEquals("first", line(lyric.textView).main.model.text)

                lyric.isDisabledVisible = false
                assertEquals(View.VISIBLE, lyric.visibility)
            }
            scenario.onActivity {
                assertEquals("second", line(lyric.textView).main.model.text)
            }
        }
    }

    @Test fun floatingWindowShowsTheFirstLineAndRecoversAfterTimeoutAndSleep() {
        ActivityScenario.launch(MediaPanelTestActivity::class.java).use { scenario ->
            lateinit var window: FloatingLyricWindow
            lateinit var lyric: StatusBarLyric
            val firstLayout = CountDownLatch(1)
            val reopenedLayout = CountDownLatch(1)
            val resumedLayout = CountDownLatch(1)
            var layoutReady = firstLayout
            val hiddenAfterTimeout = CountDownLatch(1)
            scenario.onActivity { activity ->
                val style = LyricStyle().apply {
                    basicStyle.hideOnLockScreen = false
                    basicStyle.noUpdateHideTimeout = 1
                    packageStyle.text.placeholderFormat = TextStyle.PlaceholderFormat.NONE
                    packageStyle.text.enableEnterAnim = false
                    packageStyle.anim.enable = false
                }
                lyric = StatusBarLyric(activity, style, null).apply {
                    textView.isFloatingLyric = true
                    setSong(Song(lyrics = listOf(
                        RichLyricLine(begin = 1000, end = 1999, text = "first"),
                        RichLyricLine(begin = 2000, end = 2999, text = "second"),
                        RichLyricLine(begin = 3000, end = 4000, text = "third")
                    )))
                    setPlaying(true)
                }
                val params = WindowManager.LayoutParams(
                    400,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT
                ).apply { token = activity.window.decorView.windowToken }
                window = FloatingLyricWindow(activity.windowManager, lyric, params) { layoutReady.countDown() }
                window.setEnabled(true)
            }
            try {
                assertTrue("floating window lays out after opening", firstLayout.await(5, TimeUnit.SECONDS))
                scenario.onActivity {
                    assertEquals(View.VISIBLE, lyric.windowVisibility)
                    lyric.setPosition(500)
                    assertEquals(View.GONE, lyric.visibility)
                    assertFalse(lyric.isPositionTracking)
                    lyric.setPosition(1000)
                    assertEquals("first", line(lyric.textView).main.model.text)
                    assertTrue(lyric.isPositionTracking)
                    lyric.onPositionDemandChanged = {
                        if (!lyric.isPositionTracking) hiddenAfterTimeout.countDown()
                    }
                }
                assertTrue("lyric timeout changes position demand", hiddenAfterTimeout.await(5, TimeUnit.SECONDS))
                instrumentation.waitForIdleSync()
                scenario.onActivity {
                    assertEquals(View.GONE, lyric.visibility)
                    assertEquals(View.VISIBLE, lyric.windowVisibility)
                    lyric.setPosition(1500)
                    assertEquals(View.GONE, lyric.visibility)

                    lyric.setPosition(2200)
                    assertEquals("second", line(lyric.textView).main.model.text)
                    assertTrue(lyric.isPositionTracking)
                    lyric.isSleepMode = true
                    assertFalse(lyric.isPositionTracking)
                    lyric.setPosition(3100)
                    assertEquals("second", line(lyric.textView).main.model.text)
                    lyric.isSleepMode = false
                    assertTrue(lyric.isPositionTracking)
                    assertEquals("third", line(lyric.textView).main.model.text)

                    lyric.isDisabledVisible = true
                    repeat(2) { window.setEnabled(false) }
                    assertFalse(lyric.isAttachedToWindow)
                    assertFalse(lyric.isPositionTracking)
                    lyric.setPosition(1100)
                    lyric.isDisabledVisible = false
                    layoutReady = reopenedLayout
                    repeat(2) { window.setEnabled(true) }
                }
                assertTrue("floating window lays out after reopening", reopenedLayout.await(5, TimeUnit.SECONDS))
                scenario.onActivity {
                    assertTrue(lyric.isAttachedToWindow)
                    assertTrue(lyric.isPositionTracking)
                    assertEquals("first", line(lyric.textView).main.model.text)

                    lyric.onPlayingChanged = { window.setEnabled(true) }
                    lyric.setPlaying(false)
                    assertFalse(lyric.isAttachedToWindow)
                    assertFalse(lyric.isPositionTracking)
                    lyric.setPosition(2200)
                    layoutReady = resumedLayout
                    lyric.setPlaying(true)
                }
                assertTrue("floating window lays out after resuming", resumedLayout.await(5, TimeUnit.SECONDS))
                scenario.onActivity {
                    assertTrue(lyric.isAttachedToWindow)
                    assertTrue(lyric.isPositionTracking)
                    assertEquals("second", line(lyric.textView).main.model.text)
                }
            } finally {
                scenario.onActivity { window.setEnabled(false) }
            }
        }
    }

    private fun show(floating: Boolean, preferRoma: Boolean = false, lyric: RichLyricLine = source): Pair<SuperText, LyricStyle> {
        val style = LyricStyle().apply { basicStyle.secondaryLineShowsRoma = preferRoma }
        val view = SuperText(context).apply {
            isFloatingLyric = floating
            applyStyle(style)
            song = Song(lyrics = listOf(lyric))
            seekTo(500)
        }
        return view to style
    }

    @Test fun statusSwitchUpdatesTheCurrentLineAndNeverShowsAThirdLine() = instrumentation.runOnMainSync {
        val (view, style) = show(false)
        assertEquals("翻译", line(view).secondary.model.text)
        assertEquals(View.VISIBLE, line(view).secondary.visibility)
        assertEquals(View.GONE, line(view).tertiary.visibility)
        style.basicStyle.secondaryLineShowsRoma = true
        view.applyStyle(style)
        assertEquals("romaji", line(view).secondary.model.text)
        assertEquals(View.GONE, line(view).tertiary.visibility)
        style.basicStyle.secondaryLineShowsRoma = false
        view.applyStyle(style)
        assertEquals("翻译", line(view).secondary.model.text)
    }

    @Test fun statusSwitchFallsBackInBothDirections() = instrumentation.runOnMainSync {
        listOf(false, true).forEach { preference ->
            assertEquals("翻译", line(show(false, preference, source.copy(roma = null)).first).secondary.model.text)
            assertEquals("romaji", line(show(false, preference, source.copy(translation = null)).first).secondary.model.text)
        }
    }

    @Test fun floatingShowsThreeVisibleRowsAndIgnoresTheStatusPreference() = instrumentation.runOnMainSync {
        listOf(false, true).forEach { preference ->
            val (view, _) = show(true, preference)
            val row = line(view)
            assertEquals(listOf("原歌词", "romaji", "翻译"), listOf(row.main.model.text, row.secondary.model.text, row.tertiary.model.text))
            assertEquals(listOf(View.VISIBLE, View.VISIBLE, View.VISIBLE), listOf(row.main.visibility, row.secondary.visibility, row.tertiary.visibility))
        }
    }

    @Test fun floatingCollapsesMissingRowsAndHonorsProviderVisibility() = instrumentation.runOnMainSync {
        val translation = line(show(true, lyric = source.copy(roma = null)).first)
        assertEquals("翻译", translation.secondary.model.text)
        assertEquals(View.GONE, translation.tertiary.visibility)
        val roman = line(show(true, lyric = source.copy(translation = null)).first)
        assertEquals("romaji", roman.secondary.model.text)
        assertEquals(View.GONE, roman.tertiary.visibility)
        val original = line(show(true, lyric = source.copy(translation = null, roma = null)).first)
        assertEquals(View.GONE, original.secondary.visibility)
        assertEquals(View.GONE, original.tertiary.visibility)
        val (view, _) = show(true)
        view.updateDisplayTranslation(displayRoma = false)
        assertEquals("翻译", line(view).secondary.model.text)
        assertEquals(View.GONE, line(view).tertiary.visibility)
    }

    @Test fun bothAuxiliaryRowsShareColorsAndReserveSpaceForLift() = instrumentation.runOnMainSync {
        val view = RichLyricLineView(context)
        val motion = WordMotion(cjkLiftFactor = .15f, latinLiftFactor = .15f)
        val style = LyricViewStyle(auxiliaryLayout = AuxiliaryLineLayout.ROMA_THEN_TRANSLATION,
            primary = TextLook(size = 72f), secondary = TextLook(size = 56f, color = intArrayOf(Color.BLUE)),
            highlight = Highlight(intArrayOf(Color.BLACK), intArrayOf(Color.RED)), wordMotion = motion)
        view.setStyle(style)
        view.line = source.copy(text = "友达のまま 微笑みながら", roma = "to mo da chi no ma ma ho ho e mi na ga ra", translation = "仍会以朋友的身份 带着微笑")
        view.secondary.visibility = View.VISIBLE
        view.tertiary.visibility = View.VISIBLE
        assertFalse(view.secondary.isScrollOnly)
        assertFalse(view.tertiary.isScrollOnly)
        assertEquals(view.secondary.wordMotion, view.tertiary.wordMotion)
        assertEquals(view.secondary.textPaint.color, view.tertiary.textPaint.color)
        val width = View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY)
        val height = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        view.measure(width, height)
        val romaHeight = view.secondary.measuredHeight
        val translationHeight = view.tertiary.measuredHeight
        view.setStyle(style.copy(wordMotion = motion.copy(enabled = false)))
        view.measure(width, height)
        assertTrue(romaHeight > view.secondary.measuredHeight)
        assertTrue(translationHeight > view.tertiary.measuredHeight)
    }

    @Test fun fixedStatusBarKeepsBothFullRowsWhenHeightIsLimited() = instrumentation.runOnMainSync {
        val view = SuperText(context).apply {
            setStyle(LyricViewStyle(primary = TextLook(size = 72f), secondary = TextLook(size = 56f),
                wordMotion = WordMotion(cjkLiftFactor = .15f)))
            song = Song(lyrics = listOf(source))
            seekTo(500)
        }
        val width = View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY)
        view.measure(width, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val row = line(view)
        val mainHeight = row.main.measuredHeight
        val translationHeight = row.secondary.measuredHeight
        val statusBarHeight = view.measuredHeight / 2
        listOf(View.MeasureSpec.AT_MOST, View.MeasureSpec.EXACTLY).forEach { mode ->
            view.measure(width, View.MeasureSpec.makeMeasureSpec(statusBarHeight, mode))
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            assertTrue(view.measuredHeight <= statusBarHeight)
            assertEquals("original keeps its complete glyph/animation bounds", mainHeight, row.main.measuredHeight)
            assertEquals("translation keeps its complete glyph/animation bounds", translationHeight, row.secondary.measuredHeight)
            val scale = row.height.toFloat() / (mainHeight + translationHeight)
            val top = row.height / 2f + (row.main.top - row.height / 2f) * scale
            val bottom = row.height / 2f + (row.secondary.bottom - row.height / 2f) * scale
            assertTrue(top >= -.6f)
            assertTrue(bottom <= row.height + .6f)
        }
    }

    @Test fun overlappingStatusLinesBothReceiveHeight() = instrumentation.runOnMainSync {
        val view = SuperText(context).apply {
            setStyle(LyricViewStyle(primary = TextLook(size = 72f), secondary = TextLook(size = 56f)))
            song = Song(lyrics = listOf(source, source.copy(begin = 200, text = "第二声部")))
            seekTo(500)
        }
        val width = View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY)
        view.measure(width, View.MeasureSpec.makeMeasureSpec(60, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 1000, 60)
        assertEquals(2, view.childCount)
        val rows = (0 until view.childCount).map { view.getChildAt(it) as RichLyricLineView }
        rows.forEach { assertTrue("no voice gets a zero-height viewport", it.height > 0) }
        assertTrue(rows.sumOf { it.height } <= 60)
    }
}
