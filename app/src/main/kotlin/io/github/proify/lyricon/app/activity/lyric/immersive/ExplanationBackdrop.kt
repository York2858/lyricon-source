package io.github.proify.lyricon.app.activity.lyric.immersive

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import io.github.proify.lyricon.app.activity.lyric.immersive.halcyon.LocalPlayerSurfaceActive
import io.github.proify.lyricon.app.activity.lyric.immersive.halcyon.PlayerPalette
import io.github.proify.lyricon.app.activity.lyric.immersive.halcyon.SettingsManager
import io.github.proify.lyricon.app.activity.lyric.immersive.halcyon.withCoverContentColor

@Composable
internal fun ExplanationBackdrop(state: ImmersiveLyricsState, fallback: Bitmap?, initialTitle: String, initialArtist: String) {
    val context = LocalContext.current
    val preferences by remember(context) { SettingsManager.getInstance(context) }.values.collectAsState()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val visible = lifecycle.isAtLeast(Lifecycle.State.STARTED)
    val cover = state.cover ?: fallback.takeIf {
        state.title.isBlank() || (state.title == initialTitle && state.artist == initialArtist)
    }
    val palette = remember(cover) { PlayerPalette.from(cover).withCoverContentColor() }
    CompositionLocalProvider(LocalPlayerSurfaceActive provides visible) {
        Box(Modifier.fillMaxSize().background(palette.middle)) {
            HalcyonBackdrop(cover, palette, preferences, state.playing && visible, state.currentPosition())
        }
    }
}
