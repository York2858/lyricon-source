package io.github.proify.lyricon.app.activity.lyric

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.proify.lyricon.app.R
import io.github.proify.lyricon.app.ai.explain.*
import io.github.proify.lyricon.app.compose.theme.resolveDarkMode
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Immutable
internal data class AlbumReadingColors(val background: Color, val accent: Color)

internal fun albumTrackColors(index: Int, dark: Boolean): AlbumReadingColors {
    val colors = if (dark) arrayOf(
        0xFF202D40 to 0xFFA9C4ED, 0xFF20352B to 0xFFA0CDB4, 0xFF392C23 to 0xFFE6BF9C,
        0xFF2E283F to 0xFFC9B8E8, 0xFF3B2831 to 0xFFE2AEBD, 0xFF20343C to 0xFF9BCAD8)
    else arrayOf(
        0xFFEDF3FF to 0xFF4269A2, 0xFFEDF6F0 to 0xFF3B7759, 0xFFFBF1E8 to 0xFF92613C,
        0xFFF3EFFA to 0xFF765B9D, 0xFFFAEFF2 to 0xFF955369, 0xFFEBF5F7 to 0xFF327486)
    val pair = colors[Math.floorMod(index, colors.size)]
    return AlbumReadingColors(Color(pair.first), Color(pair.second))
}

internal fun albumSectionColors(kind: AlbumReadingKind, dark: Boolean): AlbumReadingColors = when (kind) {
    AlbumReadingKind.LYRICS -> if (dark) AlbumReadingColors(Color(0xFF30263E), Color(0xFFCFB5EF))
        else AlbumReadingColors(Color(0xFFF3EDF9), Color(0xFF73539D))
    AlbumReadingKind.ARRANGEMENT -> if (dark) AlbumReadingColors(Color(0xFF3A3022), Color(0xFFE7C18C))
        else AlbumReadingColors(Color(0xFFFCF2E4), Color(0xFF8C5F23))
    AlbumReadingKind.CONTEXT -> if (dark) AlbumReadingColors(Color(0xFF213733), Color(0xFF9CD2CB))
        else AlbumReadingColors(Color(0xFFEAF5F1), Color(0xFF2F7471))
    AlbumReadingKind.GENERAL -> if (dark) AlbumReadingColors(Color(0xFF252B33), Color(0xFFBAC6D5))
        else AlbumReadingColors(Color(0xFFEFF2F6), Color(0xFF556477))
}

@Composable
internal fun AlbumTrackCard(track: AlbumExplainTrack, index: Int, working: Boolean, fontSize: Int, spacing: Int,
    showReasoning: Boolean, onEdit: () -> Unit, onGenerate: () -> Unit) {
    val dark = resolveDarkMode(LocalContext.current)
    val palette = albumTrackColors(index, dark)
    val theme = MiuixTheme.colorScheme
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(palette.background)
        .padding(16.dp).testTag("album-track-${track.number}"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.padding(top = 3.dp).width(5.dp).height(22.dp).clip(RoundedCornerShape(4.dp)).background(palette.accent))
            Text(albumTrackHeading(track), modifier = Modifier.weight(1f), fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold, color = theme.onSurface)
            if (track.status != ExplainStatus.COMPLETE) Text(when (track.status) {
                ExplainStatus.COMPLETE -> "已完成"
                ExplainStatus.GENERATING -> "解读中"
                ExplainStatus.ERROR -> "已中断"
                ExplainStatus.STOPPED -> if (track.content.isBlank()) "待解读" else "未完成"
            }, fontSize = 11.sp, color = palette.accent, modifier = Modifier.padding(top = 4.dp))
        }
        if (track.disc > 1) SmallText("CD ${track.disc}")
        if (track.instrumental) SmallText("纯器乐")
        else if (track.lyrics.isBlank()) Action("补充歌词", onEdit, enabled = !working, small = true)
        if (track.content.isNotBlank() || track.status == ExplainStatus.GENERATING) {
            TurnView(ExplainTurn(track.id, content = track.content, reasoning = track.reasoning, status = track.status),
                fontSize, spacing, showReasoning, bodyContent = {
                    AlbumReadingContent(track, dark, fontSize, spacing)
                })
        } else if (track.error.isNotBlank()) Text(track.error, color = theme.error, fontSize = 12.sp)
        if (track.error.isNotBlank() && track.content.isNotBlank()) Text(track.error, color = theme.error, fontSize = 12.sp)
        Box(Modifier.fillMaxWidth().height(1.dp).background(palette.accent.copy(alpha = .14f)))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Action("资料", onEdit, enabled = !working, small = true)
            Action(if (track.content.isNotBlank()) "重新解读" else "解读", onGenerate, enabled = !working, small = true)
        }
    }
}

@Composable
private fun AlbumReadingContent(track: AlbumExplainTrack, dark: Boolean, fontSize: Int, spacing: Int) {
    val sections = remember(track.content, track.number, track.title, track.disc) { albumReadingSections(track.content, track) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        sections.forEachIndexed { index, section ->
            val colors = albumSectionColors(section.kind, dark)
            Column(Modifier.fillMaxWidth().testTag("album-section-${track.id}-$index"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (section.kind != AlbumReadingKind.GENERAL) Row(
                    Modifier.clip(RoundedCornerShape(8.dp)).background(colors.background).padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(painterResource(when (section.kind) {
                        AlbumReadingKind.LYRICS -> R.drawable.description_24px
                        AlbumReadingKind.ARRANGEMENT -> R.drawable.ic_music_note
                        else -> R.drawable.ic_all_inclusive
                    }), contentDescription = null, tint = colors.accent, modifier = Modifier.size(15.dp))
                    Text(when (section.kind) {
                        AlbumReadingKind.LYRICS -> "歌词解读"
                        AlbumReadingKind.ARRANGEMENT -> "编曲解读"
                        AlbumReadingKind.CONTEXT -> "专辑位置"
                        else -> section.kind.title
                    }, color = colors.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                if (section.content.isNotBlank()) ExplainMarkdown(section.content, fontSize, spacing, streaming = false)
            }
        }
    }
}
