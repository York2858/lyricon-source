package io.github.proify.lyricon.app.activity.lyric.immersive

import androidx.compose.ui.unit.dp
import io.github.proify.lyricon.app.activity.lyric.immersive.halcyon.resolveAppleMusicLyricsItemOffset
import io.github.proify.lyricon.app.activity.lyric.immersive.halcyon.resolveAppleMusicLyricsLeadingPadding
import org.junit.Assert.assertEquals
import org.junit.Test

class ImmersiveLyricPositionTest {
    @Test fun portraitAndLandscapeUseIndependentPositions() {
        val portraitOnly = mapOf("portrait_lyric_position" to 15)
        assertEquals(.15f, portraitOnly.lyricFocusRatio(landscape = false), 0f)
        assertEquals(.30f, portraitOnly.lyricFocusRatio(landscape = true), 0f)

        val both = portraitOnly + ("landscape_lyric_position" to 40)
        assertEquals(.15f, both.lyricFocusRatio(landscape = false), 0f)
        assertEquals(.40f, both.lyricFocusRatio(landscape = true), 0f)
    }

    @Test fun storedPositionsAreClampedToTheSliderRange() {
        val preferences = mapOf("portrait_lyric_position" to -10, "landscape_lyric_position" to 80)
        assertEquals(.05f, preferences.lyricFocusRatio(landscape = false), 0f)
        assertEquals(.60f, preferences.lyricFocusRatio(landscape = true), 0f)
    }

    @Test fun listPaddingDoesNotShiftTheActiveLineBelowItsViewportPosition() {
        val viewport = 400.dp
        val ratio = .25f
        val padding = resolveAppleMusicLyricsLeadingPadding(viewport, ratio, minimumTopPadding = 0.dp)
        assertEquals(100.dp, padding)
        assertEquals(0.dp, resolveAppleMusicLyricsItemOffset(viewport, ratio, padding))
        assertEquals(80.dp, resolveAppleMusicLyricsItemOffset(viewport, ratio, leadingPadding = 20.dp))
    }
}
