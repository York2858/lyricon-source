package io.github.proify.lyricon.app.activity.lyric

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import io.github.proify.lyricon.app.ai.translation.TranslationLibraryViewModel
import io.github.proify.lyricon.app.compose.theme.AppTheme

class TranslationLibraryActivity : AbstractLyricActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val state = ViewModelProvider(this)[TranslationLibraryViewModel::class.java]
        setContent { AppTheme { TranslationLibraryScreen(state) { finish() } } }
    }
}
