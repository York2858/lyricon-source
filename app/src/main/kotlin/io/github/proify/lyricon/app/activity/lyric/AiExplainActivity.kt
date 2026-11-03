/* Copyright 2026 Proify, Tomakino. Licensed under the Apache License, Version 2.0. */
package io.github.proify.lyricon.app.activity.lyric

import android.graphics.Color
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.proify.lyricon.app.activity.lyric.immersive.ImmersiveLyricsState
import io.github.proify.lyricon.app.activity.lyric.immersive.ExplanationBackdrop
import io.github.proify.lyricon.app.compose.theme.appDarkColorScheme
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.LocalContentColor
import io.github.proify.lyricon.app.ai.explain.ExplainViewModel
import io.github.proify.lyricon.app.ai.explain.AlbumExplainViewModel
import io.github.proify.lyricon.app.ai.explain.explainSongFromBundle
import io.github.proify.lyricon.app.compose.theme.AppTheme
import io.github.proify.lyricon.lyric.ai.explain.AiExplainContract

/** Transparent listening sheet and full-screen reader share a lifecycle-owned conversation. */
class AiExplainActivity : AbstractLyricActivity() {
    private lateinit var state: ExplainViewModel
    private lateinit var album: AlbumExplainViewModel
    private val playback by viewModels<ImmersiveLyricsState>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        window.isNavigationBarContrastEnforced = false
        val extras = intent.extras ?: Bundle()
        val requester = if (intent.getBooleanExtra(EXTRA_FROM_IMMERSIVE, false)) {
            Messenger(Handler(Looper.getMainLooper()) { message ->
                if (message.what == AiExplainContract.REQUEST_SNAPSHOT) {
                    val snapshot = if (playback.title.isNotBlank()) playback.explanationSnapshot() else Bundle(extras)
                    runCatching { message.replyTo?.send(Message.obtain(null, AiExplainContract.RESULT_SNAPSHOT).apply { data = snapshot }) }
                }
                true
            })
        } else IntentCompat.getParcelableExtra(intent,
            AiExplainContract.EXTRA_SNAPSHOT_REQUESTER, Messenger::class.java)
        val cover = extras.getByteArray(AiExplainContract.EXTRA_COVER)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        val provider = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
                AlbumExplainViewModel::class.java -> AlbumExplainViewModel(application, explainSongFromBundle(extras))
                else -> ExplainViewModel(application, explainSongFromBundle(extras), extras.getLong(AiExplainContract.EXTRA_POSITION), requester)
            } as T
        })
        state = provider[ExplainViewModel::class.java]
        album = provider[AlbumExplainViewModel::class.java]
        setContent {
            AppTheme {
                LaunchedEffect(playback) {
                    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { playback.observe() }
                }
                MiuixTheme(colors = appDarkColorScheme()) {
                    SideEffect {
                        WindowInsetsControllerCompat(window, window.decorView).apply {
                            isAppearanceLightStatusBars = false
                            isAppearanceLightNavigationBars = false
                        }
                    }
                    CompositionLocalProvider(LocalContentColor provides MiuixTheme.colorScheme.onSurface) {
                        ExplainScreen(state,
                            openHistory = intent.getBooleanExtra(EXTRA_OPEN_HISTORY, false),
                            openQuickPrompts = intent.getBooleanExtra(EXTRA_OPEN_QUICK_PROMPTS, false), album = album,
                            openAlbum = intent.getBooleanExtra(EXTRA_OPEN_ALBUM, false),
                            background = {
                                ExplanationBackdrop(playback, cover, extras.getString(AiExplainContract.EXTRA_TITLE).orEmpty(),
                                    extras.getString(AiExplainContract.EXTRA_ARTIST).orEmpty())
                            }) {
                            state.stop(); album.stop(); finish()
                        }
                    }
                }
            }
        }
    }

    override fun onStop() {
        if (isFinishing) { state.stop(); album.stop() }
        super.onStop()
    }

    companion object {
        const val EXTRA_FROM_IMMERSIVE = "explain_from_immersive"
        const val EXTRA_OPEN_HISTORY = "open_explain_history"
        const val EXTRA_OPEN_QUICK_PROMPTS = "open_explain_quick_prompts"
        const val EXTRA_OPEN_ALBUM = "open_album_explain"
        const val EXTRA_TITLE = AiExplainContract.EXTRA_TITLE
        const val EXTRA_ARTIST = AiExplainContract.EXTRA_ARTIST
        const val EXTRA_ALBUM = AiExplainContract.EXTRA_ALBUM
        const val EXTRA_LYRICS = AiExplainContract.EXTRA_LYRICS
    }
}
