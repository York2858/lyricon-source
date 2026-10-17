package io.github.proify.lyricon.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import android.util.Log
import androidx.core.content.edit
import io.github.proify.lyricon.app.R
import io.github.proify.lyricon.app.LyriconApp
import io.github.proify.lyricon.app.bridge.AppBridgeConstants
import io.github.proify.lyricon.app.util.LyricPrefs
import io.github.proify.lyricon.lyric.style.BasicStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Serializes quick gesture toggles in the app without launching an Activity. */
class LyricGestureReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        scope.launch {
            try {
                toggleMutex.withLock {
                    val ready = withTimeoutOrNull(2_000) {
                        while (LyriconApp.xposedService == null) delay(25)
                        true
                    } == true
                    if (ready) toggle(context, intent.action)
                    else Log.w("LyriconGesture", "Remote preferences service not ready; toggle skipped")
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val toggleMutex = Mutex()
        fun toggle(context: Context, action: String?) {
            if (action != AppBridgeConstants.ACTION_TOGGLE_LYRICS &&
                action != AppBridgeConstants.ACTION_TOGGLE_FLOATING_LYRIC) return
            val toggleLyrics = action == AppBridgeConstants.ACTION_TOGGLE_LYRICS
            val prefs = if (toggleLyrics) LyricPrefs.basicStylePrefs else LyricPrefs.floatingBasicStylePrefs
            val key = if (toggleLyrics) BasicStyle.KEY_LYRICS_ENABLED
                else BasicStyle.KEY_FLOATING_MODE
            val enabled = !prefs.getBoolean(key, toggleLyrics)
            Log.i("LyriconGesture", "Toggle ${if (toggleLyrics) "lyrics" else "floating"} -> $enabled")
            prefs.edit(commit = true) { putBoolean(key, enabled) }
            if (toggleLyrics) LyricPrefs.notifyLyricModeChanged(lyricsEnabled = enabled)
            else LyricPrefs.notifyLyricModeChanged(floatingMode = enabled)
            if (toggleLyrics && !enabled) {
                Toast.makeText(context, R.string.message_lyrics_disabled, Toast.LENGTH_LONG).show()
            }
        }
    }
}
