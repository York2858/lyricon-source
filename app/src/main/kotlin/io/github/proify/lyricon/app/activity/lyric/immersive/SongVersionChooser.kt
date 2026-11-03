package io.github.proify.lyricon.app.activity.lyric.immersive

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.proify.lyricon.app.activity.lyric.immersive.halcyon.PlayerPalette

internal data class SongVersionChoice(val id: String, val title: String, val detail: String)

@Composable
internal fun SongVersionChooser(choices: List<SongVersionChoice>, cover: Bitmap?, palette: PlayerPalette,
    preferences: Map<String, *>, font: FontFamily, playing: Boolean, position: Long, busy: Boolean,
    onDismiss: () -> Unit, onSelect: (SongVersionChoice) -> Unit) {
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.padding(24.dp).widthIn(max = 440.dp).fillMaxWidth().heightIn(max = 560.dp)
            .clip(RoundedCornerShape(24.dp)).background(palette.middle).testTag("song_version_chooser")) {
            Box(Modifier.matchParentSize()) { HalcyonBackdrop(cover, palette, preferences, playing, position) }
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
                Text("选择歌曲版本", fontFamily = font, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    color = palette.onBackground, modifier = Modifier.padding(bottom = 12.dp))
                choices.forEach { choice ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .clickable(enabled = !busy) { onSelect(choice) }.padding(horizontal = 12.dp, vertical = 14.dp)) {
                        Text(choice.title, fontFamily = font, fontSize = 16.sp,
                            fontWeight = FontWeight(preferences.number("font_weight", 800).coerceIn(100, 900)), color = palette.onBackground)
                        Text(choice.detail, fontFamily = font, fontSize = 12.sp, color = palette.onBackground.copy(alpha = .65f))
                    }
                }
                TextButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (busy) "正在打开…" else "取消", fontFamily = font, color = palette.onBackground)
                }
            }
        }
    }
}
