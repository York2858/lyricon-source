package io.github.proify.lyricon.app.activity.lyric.immersive

import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import io.github.proify.lyricon.app.activity.BaseActivity
import io.github.proify.lyricon.app.activity.lyric.immersive.halcyon.PlayerPalette
import io.github.proify.lyricon.app.activity.lyric.immersive.halcyon.SettingsManager
import io.github.proify.lyricon.app.activity.lyric.immersive.halcyon.withCoverContentColor
import io.github.proify.lyricon.app.bridge.ImmersiveLyricsContract as Contract
import io.github.proify.lyricon.app.compose.theme.AppTheme
import kotlinx.coroutines.launch

/** Only the file-version chooser; opening Lyrico never renders the lyric page. */
class MusicTagChoicesActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        val choices = intent.getParcelableArrayListExtra<Bundle>("choices").orEmpty().map {
            SongVersionChoice(it.getString("choice_id").orEmpty(), it.getString("title").orEmpty(), it.getString("detail").orEmpty())
        }.filter { it.id.isNotBlank() }
        if (choices.isEmpty()) { finish(); return }
        val bytes = intent.getByteArrayExtra("cover")
        val cover = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        setContent {
            AppTheme {
                val manager = remember { SettingsManager.getInstance(this) }
                val prefs by manager.values.collectAsState()
                val palette = remember(cover) { PlayerPalette.from(cover).withCoverContentColor() }
                val font = rememberHalcyonFont(prefs["original_font"] as? String, prefs.number("font_weight", 800))
                var busy by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()
                SongVersionChooser(choices, cover, palette, prefs, font, false, 0, busy,
                    onDismiss = { finish() }, onSelect = { choice ->
                        scope.launch {
                            busy = true
                            try {
                                val reply = ImmersiveLyricsCommands.request(applicationContext, Contract.OPEN_MUSIC_TAGS,
                                    Bundle().apply {
                                        putString("identity", intent.getStringExtra("identity"))
                                        putString("token", intent.getStringExtra("token"))
                                        putString("choice_id", choice.id)
                                    })
                                check(reply.getBoolean("accepted")) { reply.getString("error") ?: "打开歌曲失败" }
                                finish()
                            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                            catch (error: Exception) {
                                Toast.makeText(this@MusicTagChoicesActivity, error.message, Toast.LENGTH_LONG).show()
                            } finally { busy = false }
                        }
                    })
            }
        }
    }
}
