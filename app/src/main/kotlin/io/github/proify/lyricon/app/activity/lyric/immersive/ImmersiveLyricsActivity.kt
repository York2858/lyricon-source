package io.github.proify.lyricon.app.activity.lyric.immersive

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.proify.lyricon.app.activity.BaseActivity
import io.github.proify.lyricon.app.compose.theme.AppTheme

class ImmersiveLyricsActivity : BaseActivity() {
    private val state: ImmersiveLyricsState by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                LaunchedEffect(state) {
                    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { state.observe() }
                }
                ImmersiveLyricsScreen(state)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }
}
