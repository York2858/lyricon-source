package io.github.proify.lyricon.xposed.systemui.lyric

import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.lyric.style.LyricStyle
import io.github.proify.lyricon.lyric.view.RichLyricLineView
import io.github.proify.lyricon.statusbarlyric.SuperText
import io.github.proify.lyricon.lyric.view.LyricViewStyle
import io.github.proify.lyricon.lyric.view.AuxiliaryLineLayout
import io.github.proify.lyricon.lyric.view.TextLook
import io.github.proify.lyricon.lyric.view.Highlight
import io.github.proify.lyricon.lyric.view.WordMotion
import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test

/** Exercises the actual view/style path in an isolated APK without touching player settings. */
class LyricAuxiliaryDisplayTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val source = RichLyricLine(begin = 0, end = 8000, text = "原歌词", roma = "romaji", translation = "翻译")
    private fun line(view: SuperText) = view.getChildAt(0) as RichLyricLineView
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
