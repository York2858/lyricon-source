package io.github.proify.lyricon.app.activity.lyric

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
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
import io.github.proify.lyricon.app.compose.MaterialPalette
import io.github.proify.lyricon.app.util.AppThemeUtils
import androidx.compose.ui.platform.LocalContext
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
internal fun LibraryTextAction(text: String, enabled: Boolean = true, selected: Boolean = false, modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null, onClick: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    Box(modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp))
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 10.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        val tint = (if (selected) colors.primary else colors.onSurfaceVariantSummary).copy(alpha = if (enabled) 1f else .38f)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (icon != null) Icon(painterResource(icon), null, Modifier.size(17.dp), tint = tint)
            Text(text, fontSize = 13.sp, color = tint)
        }
    }
}

@Composable
internal fun LibraryTab(text: String, selected: Boolean, @DrawableRes icon: Int, tint: Color,
    modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    val accent = if (AppThemeUtils.isEnableMonet(LocalContext.current)) colors.primary else tint
    Column(modifier.height(76.dp).clickable(role = Role.Tab, onClick = onClick)
        .semantics { this.selected = selected }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
        Icon(painterResource(icon), null, Modifier.padding(top = 15.dp).size(23.dp), tint = accent)
        Text(text, fontSize = 12.sp, color = colors.onSurface)
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(2.dp)
            .background(if (selected) if (AppThemeUtils.isEnableMonet(LocalContext.current)) colors.primary else MaterialPalette.Purple.Primary else Color.Transparent))
    }
}

internal fun librarySongAccent(index: Int): Color = when (Math.floorMod(index, 4)) {
    0 -> MaterialPalette.Teal.Primary
    1 -> MaterialPalette.Orange.Primary
    2 -> MaterialPalette.Blue.Primary
    else -> MaterialPalette.Purple.Primary
}

@Composable
internal fun LibraryIconBadge(@DrawableRes icon: Int, color: Color, size: androidx.compose.ui.unit.Dp = 40.dp) {
    val colors = MiuixTheme.colorScheme
    val monet = AppThemeUtils.isEnableMonet(LocalContext.current)
    Box(Modifier.size(size).clip(CircleShape).background(if (monet) colors.primary else color), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), null, Modifier.size(size * .56f), tint = if (monet) colors.onPrimary else Color.White)
    }
}

@Composable
internal fun LibraryTimingBadge(text: String) {
    val colors = MiuixTheme.colorScheme
    Text(text, fontSize = 10.sp, color = colors.onSurfaceVariantSummary, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(7.dp)).background(colors.onSurface.copy(alpha = .05f))
            .padding(horizontal = 7.dp, vertical = 3.dp))
}

@Composable
internal fun LibraryReaderSetting(title: String, value: String, @DrawableRes icon: Int, color: Color,
    modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    Row(modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LibraryIconBadge(icon, color, 36.dp)
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Text(value, fontSize = 12.sp, color = colors.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 138.dp))
        Icon(painterResource(R.drawable.keyboard_arrow_right_24px), null, Modifier.size(16.dp), tint = colors.onSurfaceVariantSummary)
    }
}

@Composable
internal fun LibraryReaderAction(text: String, @DrawableRes icon: Int, modifier: Modifier = Modifier,
    enabled: Boolean = true, onClick: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    Row(modifier.heightIn(min = 56.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically) {
        val color = colors.onSurface.copy(alpha = if (enabled) 1f else .38f)
        Icon(painterResource(icon), null, Modifier.size(22.dp), tint = color)
        Text(text, fontSize = 14.sp, color = color)
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
    diagnostic: Boolean, accentIndex: Int = 0, onClick: () -> Unit, onLongClick: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    val shape = RoundedCornerShape(topStart = if (first) 20.dp else 0.dp, topEnd = if (first) 20.dp else 0.dp,
        bottomStart = if (last) 20.dp else 0.dp, bottomEnd = if (last) 20.dp else 0.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(colors.surfaceContainer)
        .combinedClickable(role = Role.Button, onClick = onClick, onLongClickLabel = "选择这首歌曲", onLongClick = onLongClick)
        .semantics { if (selecting) this.selected = selected }) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (selecting) {
                Box(Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(if (selected) colors.primary else Color.Transparent)
                    .border(1.dp, if (selected) colors.primary else colors.onSurfaceVariantSummary, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                    if (selected) Text("✓", color = colors.onPrimary, fontSize = 15.sp)
                }
            } else {
                LibraryIconBadge(R.drawable.library_music, librarySongAccent(accentIndex))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(song.title.ifBlank { "未知歌曲（旧记录）" }, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (!diagnostic) LibraryTimingBadge(translationTimingLabel(song))
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
        if (!last) HorizontalDivider(Modifier.padding(start = 72.dp, end = 16.dp), color = colors.onSurface.copy(alpha = .06f))
    }
}
