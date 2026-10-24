package io.github.proify.lyricon.app.activity.lyric

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.proify.lyricon.app.R
import io.github.proify.lyricon.lyric.ai.translate.*
import androidx.compose.material3.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

internal fun translationLibraryDateGroup(time: Long, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): String {
    if (time <= 0) return "时间未记录"
    val date = Instant.ofEpochMilli(time).atZone(zone).toLocalDate()
    return when (date) {
        today -> "今天"
        today.minusDays(1) -> "昨天"
        else -> if (date > today) "未来日期" else "更早"
    }
}

@Composable
internal fun LibraryIconAction(label: String, @DrawableRes icon: Int, enabled: Boolean = true,
    tint: Color = MiuixTheme.colorScheme.onSurface, rotation: Float = 0f, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).clip(RoundedCornerShape(24.dp)).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), null, Modifier.size(22.dp).rotate(rotation), tint = tint.copy(alpha = if (enabled) 1f else .38f))
    }
}

@Composable
internal fun LibraryTextAction(text: String, enabled: Boolean = true, selected: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    Box(modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp))
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 10.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 13.sp, color = (if (selected) colors.primary else colors.onSurfaceVariantSummary).copy(alpha = if (enabled) 1f else .38f))
    }
}

@Composable
internal fun LibraryTab(text: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    Column(Modifier.widthIn(min = 56.dp).height(48.dp).clickable(role = Role.Tab, onClick = onClick)
        .semantics { this.selected = selected }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
        Text(text, fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) colors.primary else colors.onSurfaceVariantSummary, modifier = Modifier.padding(top = 12.dp))
        Box(Modifier.width(24.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(if (selected) colors.primary else Color.Transparent))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibrarySheet(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val colors = MiuixTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surfaceContainer, contentColor = colors.onSurface, tonalElevation = 0.dp,
        dragHandle = { Box(Modifier.padding(top = 12.dp, bottom = 8.dp).size(32.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(colors.onSurface.copy(alpha = .18f))) }) {
        Column(Modifier.fillMaxWidth().imePadding().padding(start = 22.dp, end = 22.dp, bottom = 20.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                LibraryTextAction("关闭", onClick = onDismiss)
            }
            content()
        }
    }
}

@Composable
internal fun LibrarySettingRow(title: String, content: @Composable RowScope.() -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 58.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, fontSize = 14.sp, modifier = Modifier.weight(1f))
            content()
        }
        HorizontalDivider(color = MiuixTheme.colorScheme.onSurface.copy(alpha = .07f))
    }
}

@Composable
internal fun LibraryMenuAction(text: String, @DrawableRes icon: Int, enabled: Boolean = true, destructive: Boolean = false, onClick: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 8.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        val color = (if (destructive) colors.error else colors.onSurface).copy(alpha = if (enabled) 1f else .38f)
        Icon(painterResource(icon), null, Modifier.size(21.dp), tint = color)
        Text(text, fontSize = 15.sp, color = color)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun LibrarySongRow(song: TranslationSongEntry, first: Boolean, last: Boolean, selecting: Boolean, selected: Boolean,
    diagnostic: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    val shape = RoundedCornerShape(topStart = if (first) 20.dp else 0.dp, topEnd = if (first) 20.dp else 0.dp,
        bottomStart = if (last) 20.dp else 0.dp, bottomEnd = if (last) 20.dp else 0.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(colors.surfaceContainer)
        .combinedClickable(role = Role.Button, onClick = onClick, onLongClickLabel = "选择这首歌曲", onLongClick = onLongClick)
        .semantics { if (selecting) this.selected = selected }) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (selecting) {
                Box(Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(if (selected) colors.primary else Color.Transparent)
                    .border(1.dp, if (selected) colors.primary else colors.onSurfaceVariantSummary, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                    if (selected) Text("✓", color = colors.onPrimary, fontSize = 15.sp)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(song.title.ifBlank { "未知歌曲（旧记录）" }, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (!diagnostic) Text(translationTimingLabel(song), fontSize = 11.sp,
                        color = if (song.wordTimedVersions > 0) colors.primary else colors.onSurfaceVariantSummary,
                        modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(if (song.wordTimedVersions > 0) colors.primary.copy(alpha = .10f) else colors.onSurface.copy(alpha = .04f)).padding(horizontal = 6.dp, vertical = 2.dp))
                }
                Text("${song.artist.ifBlank { "歌手未记录" }} · ${song.languages.ifBlank { "语言未记录" }}", fontSize = 12.sp, color = colors.onSurfaceVariantSummary)
                val date = if (song.updatedAt <= 0) "时间未记录" else if (translationLibraryDateGroup(song.updatedAt) in setOf("今天", "昨天"))
                    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(song.updatedAt))
                else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(song.updatedAt))
                Text("$date · ${song.versions} 个版本", fontSize = 12.sp, color = colors.onSurfaceVariantSummary)
                if (diagnostic) Text("翻译 ${song.translated} · 跳过 ${song.skipped} · 报错 ${song.errors}", fontSize = 12.sp, color = colors.onSurfaceVariantSummary)
            }
            if (song.favorites > 0) Text("★", fontSize = 16.sp, color = colors.primary)
            if (!selecting) Icon(painterResource(R.drawable.keyboard_arrow_right_24px), null, Modifier.size(18.dp), tint = colors.onSurfaceVariantSummary)
        }
        if (!last) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = colors.onSurface.copy(alpha = .06f))
    }
}
