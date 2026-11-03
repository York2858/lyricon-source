package io.github.proify.lyricon.app.activity.lyric.immersive

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.proify.lyricon.app.activity.lyric.immersive.halcyon.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

internal fun immersivePreferences(context: Context) = SettingsManager.getInstance(context).preferences
internal fun Map<String, *>.flag(key: String, default: Boolean) = this[key] as? Boolean ?: default
internal fun Map<String, *>.number(key: String, default: Int) = (this[key] as? Number)?.toInt() ?: default

@Composable
internal fun ImmersiveSettingsContent(manager: SettingsManager, values: Map<String, *>) {
    var page by remember { mutableStateOf("main") }
    val scrollState = key(page) { rememberScrollState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var fontTarget by remember { mutableStateOf("original_font") }
    var notice by remember { mutableStateOf<String?>(null) }
    val importBackground = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                manager.set("custom_background_uri", uri.toString())
            }.onFailure { notice = "无法读取背景图片，请重新选择" }
        }
    }
    val importFont = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val target = fontTarget
            scope.launch {
                val path = withContext(Dispatchers.IO) {
                    runCatching {
                        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readNBytes(20_000_001) }
                            ?: error("无法读取字体")
                        require(bytes.size in 1..20_000_000) { "字体不得超过 20 MB" }
                        val file = File(context.filesDir, "$target-${java.util.UUID.randomUUID()}.ttf")
                        val staging = File(context.cacheDir, "$target-importing.ttf")
                        staging.writeBytes(bytes)
                        android.graphics.Typeface.createFromFile(staging)
                        staging.copyTo(file, overwrite = true)
                        staging.delete()
                        file.absolutePath
                    }
                }
                path.onSuccess {
                    val previous = values[target] as? String
                    manager.set(target, it)
                    previous?.let(::File)?.takeIf { old ->
                        old.parentFile == context.filesDir && old.name.startsWith("$target-")
                    }?.delete()
                    notice = "字体已应用"
                }.onFailure { notice = "字体导入失败，原设置已保留" }
            }
        }
    }
    Column(
        Modifier.fillMaxWidth()
            .heightIn(max = 720.dp)
            .verticalScroll(scrollState)
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
            .testTag("immersive_settings")
    ) {
        if (page != "main") {
            TextButton(onClick = { page = "main" }, modifier = Modifier.testTag("settings_back")) {
                Text("返回歌词显示")
            }
        }
        Text(
            when (page) {
                "style" -> "歌词样式"
                "background" -> "歌词背景"
                "font" -> "歌词字体"
                else -> "歌词显示"
            },
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp)
        )
        when (page) {
            "main" -> {
                SettingsCard {
                    PreferenceToggle("显示罗马音 / 注音", "roma", true, manager, values)
                    if (values.flag("roma", true)) {
                        PreferenceToggle("注音显示在原文下方", "pronunciation_below", false, manager, values)
                    }
                    PreferenceToggle("显示翻译", "translation", true, manager, values)
                    PreferenceToggle("逐字上浮", "word_lift", true, manager, values)
                    PreferenceToggle("屏幕常亮", "keep_screen_on", false, manager, values)
                    PreferenceToggle("全屏显示（隐藏状态栏）", "fullscreen", false, manager, values)
                    PreferenceToggle("彩虹歌词", "rainbow", false, manager, values)
                    PreferenceToggle("HDR 歌词高亮", "hdr", false, manager, values)
                    PreferenceToggle("延音动效", "sustain_motion", true, manager, values)
                    PreferenceToggle("透视效果", "perspective", false, manager, values)
                    PreferenceArrow("歌词样式", onClick = { page = "style" })
                    PreferenceArrow("歌词背景", onClick = { page = "background" })
                    PreferenceArrow("歌词字体", onClick = { page = "font" })
                }
                Spacer(Modifier.height(16.dp))
                SettingsCard {
                    PreferenceSlider("延音触发时长（毫秒）", "sustain_threshold", 1200, 300..3000, manager, values)
                    PreferenceToggle("显示背景人声", "backing_vocals", true, manager, values)
                    PreferenceToggle("暂停时仅高亮当前歌词", "pause_current_only", true, manager, values)
                    PreferenceToggle("点击歌词跳转并播放", "click_seek", true, manager, values)
                    if (values.flag("click_seek", true)) {
                        PreferenceToggle("逐字精确跳转", "word_seek", false, manager, values)
                    }
                    PreferenceToggle("歌词触摸反馈", "touch_feedback", false, manager, values)
                    PreferenceSlider("歌词偏移（毫秒）", "offset", 0, -5000..5000, manager, values)
                }
            }
            "style" -> SettingsCard {
                PreferenceSlider("歌名字号", "title_size", 22, 12..36, manager, values)
                PreferenceSlider("歌手字号", "artist_size", 14, 10..28, manager, values)
                PreferenceChoice(
                    title = "歌词对齐",
                    items = listOf("左对齐", "居中", "右对齐"),
                    selectedIndex = values.number("align", 0).coerceIn(0, 2),
                    onSelectedIndexChange = { manager.set("align", it) }
                )
                if (values.flag("perspective", false)) {
                    PreferenceSlider("透视角度", "perspective_angle", 25, 0..45, manager, values)
                }
                PreferenceSlider("非当前行模糊（%）", "non_current_blur", 70, 0..100, manager, values)
                PreferenceSlider("歌词缩放（%）", "font_scale", 100, 50..150, manager, values)
                PreferenceSlider("歌词字号", "primary_size", 32, 16..60, manager, values)
                PreferenceSlider("副行缩放（%）", "secondary_scale", 100, 50..150, manager, values)
                PreferenceSlider("副行字号", "secondary_size", 16, 10..36, manager, values)
                PreferenceSlider("字重", "font_weight", 800, 100..900, manager, values)
                if (values.flag("hdr", false)) {
                    PreferenceSlider("HDR 高亮亮度（%）", "hdr_brightness", 200, 150..250, manager, values)
                }
            }
            "background" -> SettingsCard {
                val mode = values.number("background_mode", 0).coerceIn(0, 4)
                PreferenceChoice(
                    title = "背景样式",
                    items = listOf("Apple Music 封面流光", "Beautiful Lyrics", "封面模糊", "封面渐变", "自定义图片"),
                    selectedIndex = mode,
                    onSelectedIndexChange = { manager.set("background_mode", it) }
                )
                if (mode == 0) {
                    PreferenceToggle("背景流动", "flow_enabled", true, manager, values)
                    PreferenceSlider("Apple Music 流光速度", "apple_flow_speed", 10, 5..60, manager, values)
                    PreferenceSlider("Apple Music 流光模糊", "apple_flow_blur", 60, 30..100, manager, values)
                }
                if (mode == 1) {
                    PreferenceSlider("Beautiful Lyrics 速度", "beautiful_speed", 25, 5..60, manager, values)
                    PreferenceSlider("Beautiful Lyrics 模糊", "beautiful_blur", 32, 0..80, manager, values)
                    PreferenceSlider("Beautiful Lyrics 亮度", "beautiful_brightness", 70, 30..120, manager, values)
                }
                if (mode == 4) {
                    PreferenceArrow("选择背景图片", onClick = { importBackground.launch(arrayOf("image/*")) })
                    PreferenceSlider("图片不透明度（%）", "custom_opacity", 100, 20..100, manager, values)
                    PreferenceSlider("背景暗化（%）", "custom_dim", 26, 0..80, manager, values)
                    notice?.let { SettingNotice(it) }
                }
            }
            "font" -> SettingsCard {
                listOf("original_font" to "原文", "translation_font" to "翻译 / 注音").forEach { (key, label) ->
                    val path = values[key] as? String ?: ""
                    PreferenceChoice(
                        title = "$label 字体",
                        items = listOf("Halcyon 默认（Inter / MiSans）", "MiSans Bold", "Inter Bold", "系统字体", "导入字体"),
                        selectedIndex = when (path) {
                            "" -> 0
                            "misans" -> 1
                            "inter" -> 2
                            "system" -> 3
                            else -> 4
                        },
                        onSelectedIndexChange = { index ->
                            when (index) {
                                0 -> manager.set(key, "")
                                1 -> manager.set(key, "misans")
                                2 -> manager.set(key, "inter")
                                3 -> manager.set(key, "system")
                                else -> {
                                    fontTarget = key
                                    importFont.launch(arrayOf("font/*", "application/x-font-ttf", "application/octet-stream"))
                                }
                            }
                        }
                    )
                }
                notice?.let { SettingNotice(it) }
            }
        }
        TextButton(
            onClick = { manager.preferences.edit().clear().apply() },
            modifier = Modifier.testTag("settings_reset")
        ) {
            Text("恢复默认设置")
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF242424))
    ) {
        Column(content = content)
    }
}

@Composable
private fun PreferenceToggle(
    title: String,
    key: String,
    default: Boolean,
    manager: SettingsManager,
    values: Map<String, *>
) {
    val checked = values.flag(key, default)
    Row(
        modifier = Modifier.fillMaxWidth()
            .testTag("lyric_setting_$key")
            .toggleable(value = checked, role = Role.Switch, onValueChange = { manager.set(key, it) })
            .padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, modifier = Modifier.weight(1f), fontSize = 16.sp)
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF267FF3),
                checkedBorderColor = Color.Transparent, uncheckedThumbColor = Color.White,
                uncheckedTrackColor = Color(0xFF515151), uncheckedBorderColor = Color.Transparent
            ),
            modifier = Modifier.testTag("lyric_switch_$key")
        )
    }
    HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun PreferenceArrow(title: String, summary: String? = null, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp)
            summary?.let {
                Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(">", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun PreferenceChoice(
    title: String,
    items: List<String>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    PreferenceArrow(title = title, summary = items[selectedIndex], onClick = { expanded = true })
    if (expanded) {
        AlertDialog(
            onDismissRequest = { expanded = false },
            title = { Text(title) },
            text = {
                Column {
                    items.forEachIndexed { index, item ->
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clickable {
                                    onSelectedIndexChange(index)
                                    expanded = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = index == selectedIndex, onClick = null)
                            Text(item, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { expanded = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun PreferenceSlider(
    title: String,
    key: String,
    default: Int,
    range: IntRange,
    manager: SettingsManager,
    values: Map<String, *>
) {
    val value = values.number(key, default).coerceIn(range)
    Column(
        Modifier.fillMaxWidth()
            .testTag("lyric_slider_$key")
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, fontSize = 15.sp)
            Text(value.toString(), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { manager.set(key, it.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat()
        )
    }
    HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun SettingNotice(text: String) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(
            text,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 14.sp
        )
    }
}
