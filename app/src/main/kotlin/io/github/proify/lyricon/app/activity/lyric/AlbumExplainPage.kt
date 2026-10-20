package io.github.proify.lyricon.app.activity.lyric

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.runtime.*
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.github.proify.lyricon.app.ai.explain.*
import io.github.proify.lyricon.app.ai.translation.TranslationExportPicker
import io.github.proify.lyricon.app.util.toast
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun AlbumExplainPage(state: AlbumExplainViewModel, fontSize: Int, spacing: Int, showReasoning: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current; val clipboard = LocalClipboard.current; val scope = rememberCoroutineScope()
    val colors = MiuixTheme.colorScheme
    var editor by rememberSaveable { mutableStateOf(true) }
    var history by rememberSaveable { mutableStateOf(false) }
    var historyQuery by rememberSaveable { mutableStateOf("") }
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    var editingTrack by rememberSaveable { mutableStateOf<String?>(null) }
    var showInfo by rememberSaveable { mutableStateOf(false) }
    var showNotes by rememberSaveable { mutableStateOf(false) }
    var albumMenu by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<AlbumExplainRecord?>(null) }
    val list = rememberLazyListState()
    LaunchedEffect(state.notice) { state.notice?.let { toast(it); state.clearNotice() } }
    LaunchedEffect(state.record?.id) { if (state.record?.tracks?.isNotEmpty() == true) editor = false }
    BackHandler(history) { history = false }
    val audioPermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) state.loadLocalAlbum() else state.notify("未允许读取音频，仍可手动填写曲目或用 AI 补全")
    }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) runCatching { TranslationExportPicker.takeFolderGrant(context, result.resultCode, result.data) }
            .onSuccess { state.notify("歌词文件夹已授权，重新本地导入可载入同名 LRC、TTML 或 TXT") }
            .onFailure { state.notify(it.message ?: "授权失败，请进入 Music 或具体子文件夹") }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri -> if (uri != null) state.exportText(uri) }
    Column(modifier.testTag("album-explain-page")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Action(if (history) "返回专辑" else "历史", { history = !history }, enabled = !state.working, small = true)
            if (!history) {
                Action(if (editor) "收起" else "编辑", { editor = !editor }, enabled = !state.working, small = true)
                Action(if (state.record?.favorite == true) "已收藏" else "收藏", state::favorite,
                    enabled = !state.working && state.record?.tracks?.any { it.content.isNotBlank() } == true, small = true)
            }
        }
        if (state.busy && !state.generating) Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            SmallText("正在准备专辑资料…", modifier = Modifier.weight(1f))
            Action("停止", state::stop, small = true)
        }
        if (history) {
            val filtered = remember(state.records, historyQuery, favoritesOnly) {
                val query = historyQuery.trim()
                state.records.filter { (!favoritesOnly || it.favorite) &&
                    (query.isEmpty() || it.title.contains(query, true) || it.artist.contains(query, true)) }
            }
            Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp)) {
                InputBox(historyQuery, { historyQuery = it }, "专辑 / 歌手", singleLine = true,
                    modifier = Modifier.padding(vertical = 10.dp).testTag("album-history-search"))
                Segments(listOf("全部", "收藏"), if (favoritesOnly) 1 else 0) { favoritesOnly = it == 1 }
                LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("album-history-list"), contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (filtered.isEmpty()) item { SmallText(when {
                    historyQuery.isNotBlank() -> "未找到匹配的专辑"
                    favoritesOnly -> "暂无收藏的专辑"
                    else -> "暂无解读记录"
                }) }
                items(filtered, key = { it.id }) { saved -> Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.surfaceContainer).padding(14.dp)) {
                    Text(saved.title, color = colors.onSurface, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    SmallText("${saved.artist} · ${saved.tracks.count { it.status == ExplainStatus.COMPLETE }}/${saved.tracks.size} 已解读" + if (saved.favorite) " · 已收藏" else "")
                    Row { Action("打开专辑", { state.openHistory(saved); history = false }); Action("删除", { pendingDelete = saved }) }
                } }
                }
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("album-track-list"), state = list,
                contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (editor) item("editor") { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("专辑与曲目", color = colors.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    InputBox(state.albumTitle, { state.albumTitle = it.take(300) }, "专辑名称", singleLine = true, enabled = !state.working,
                        modifier = Modifier.testTag("album-title"))
                    InputBox(state.artist, { state.artist = it.take(300) }, "专辑艺人", singleLine = true, enabled = !state.working,
                        modifier = Modifier.testTag("album-artist"))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Action("本地导入", {
                            if (ContextCompat.checkSelfPermission(context, audioPermission) == PackageManager.PERMISSION_GRANTED) state.loadLocalAlbum()
                            else permission.launch(audioPermission)
                        }, enabled = !state.working, filled = true, small = true)
                        Action("AI 补全", state::suggestTracks, enabled = !state.working, filled = true, small = true)
                        Action("歌词目录", { folder.launch(TranslationExportPicker.folderIntent(context,
                            DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:Music"))) },
                            enabled = !state.working, small = true)
                    }
                    SmallText("每行一首，按顺序解读。")
                    if (state.record?.source?.contains("AI 建议") == true) SmallText("请核对专辑版本与曲序。")
                    InputBox(state.trackList, { state.trackList = it.take(24000) }, "1. 曲名\n2. 曲名\n3. Holy, Holy\n4. The New Sound", minLines = 4,
                        enabled = !state.working, modifier = Modifier.heightIn(max = 240.dp).testTag("album-tracks-input"))
                    Action(if (showNotes) "收起补充资料" else "补充资料", { showNotes = !showNotes }, small = true)
                    if (showNotes) InputBox(state.referenceNotes, { state.referenceNotes = it.take(12000) }, "参考资料或听感笔记（选填）", minLines = 2,
                        enabled = !state.working, modifier = Modifier.heightIn(max = 180.dp))
                    Action("保存曲目", { if (state.applyTracks()) editor = false }, enabled = !state.working, active = true)
                } }
                state.record?.let { record ->
                    item("summary") { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(record.title, color = colors.onSurface, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        SmallText("${record.artist} · ${record.tracks.count { it.status == ExplainStatus.COMPLETE }}/${record.tracks.size} 已解读")
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            if (record.tracks.any { it.status != ExplainStatus.COMPLETE }) Action(
                                if (record.tracks.any { it.content.isNotBlank() }) "继续解读" else "开始解读",
                                { state.generate() }, enabled = !state.working, active = true)
                            if (state.working) Action("停止生成", state::stop, active = true)
                            Spacer(Modifier.weight(1f))
                            Box {
                                Action("更多", { albumMenu = true }, small = true)
                                DropdownMenu(expanded = albumMenu, onDismissRequest = { albumMenu = false }, containerColor = colors.surfaceContainer) {
                                    DropdownMenuItem(text = { Text("解读说明") }, onClick = { albumMenu = false; showInfo = true })
                                    DropdownMenuItem(text = { Text("复制全文") }, enabled = record.tracks.any { it.content.isNotBlank() }, onClick = {
                                        albumMenu = false
                                        scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("专辑逐曲解读", albumPlainText(record)))) }
                                    })
                                    DropdownMenuItem(text = { Text("导出 TXT") }, enabled = record.tracks.any { it.content.isNotBlank() }, onClick = {
                                        albumMenu = false
                                        val name = "${record.artist} - ${record.title}".map { if (it in "\\/:*?\"<>|" || it.isISOControl()) '_' else it }.joinToString("").take(100)
                                        export.launch("$name-逐曲解读.txt")
                                    })
                                }
                            }
                        }
                        Row(Modifier.horizontalScroll(rememberScrollState())) { record.tracks.forEachIndexed { index, track ->
                            Action("${track.number}. ${track.title}", { scope.launch { list.animateScrollToItem((if (editor) 2 else 1) + index) } }, small = true, filled = true)
                        } }
                    } }
                    itemsIndexed(record.tracks, key = { _, it -> it.id }) { index, track ->
                        AlbumTrackCard(track, index, state.working, fontSize, spacing, showReasoning,
                            onEdit = { editingTrack = track.id }, onGenerate = { state.generate(track.id) })
                    }
                }

            }
        }
    }
    editingTrack?.let { id -> state.record?.tracks?.firstOrNull { it.id == id }?.let { track ->
        var lyrics by rememberSaveable(id) { mutableStateOf(track.lyrics) }
        var notes by rememberSaveable(id) { mutableStateOf(track.notes) }
        var instrumental by rememberSaveable(id) { mutableStateOf(track.instrumental) }
        var confirmSave by rememberSaveable(id) { mutableStateOf(false) }
        fun save() { state.editTrack(id, lyrics, notes, instrumental); editingTrack = null }
        WindowDialog(show = !confirmSave, title = "${track.number}. ${track.title}", onDismissRequest = { editingTrack = null }) {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Text("纯器乐曲", color = colors.onSurface, modifier = Modifier.weight(1f)); Switch(instrumental, { instrumental = it }) }
                InputBox(lyrics, { lyrics = it.take(20000) }, "歌词", minLines = 4, enabled = !instrumental, modifier = Modifier.heightIn(max = 220.dp))
                InputBox(notes, { notes = it.take(8000) }, "编曲笔记（选填）", minLines = 3, modifier = Modifier.heightIn(max = 180.dp))
            }
            Row { Action("取消", { editingTrack = null }); Action("保存", {
                if (track.content.isNotBlank() && (lyrics != track.lyrics || notes != track.notes || instrumental != track.instrumental)) confirmSave = true
                else save()
            }, active = true) }
        }
        if (confirmSave) WindowDialog(show = true, title = "保存后需重新解读本曲", onDismissRequest = { confirmSave = false }) {
            Text("本曲的旧解读将清空，已收藏的专辑版本会保留。", color = colors.onSurface)
            Row { Action("取消", { confirmSave = false }); Action("保存", { confirmSave = false; save() }, active = true) }
        }
    } }
    if (showInfo) WindowDialog(show = true, title = "解读说明", onDismissRequest = { showInfo = false }) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(state.record?.source.orEmpty(), color = colors.onSurface)
            SmallText("未输入音频。编曲判断依据参考资料与作品知识，未经音频实测。")
            Action("知道了", { showInfo = false })
        }
    }
    pendingDelete?.let { value -> WindowDialog(show = true, title = "删除《${value.title}》的这版解读？", onDismissRequest = { pendingDelete = null }) {
        Text("会删除这张专辑的本次逐曲解读，其他版本保留。", color = colors.onSurface)
        Row { Action("取消", { pendingDelete = null }); Action("删除", { state.deleteHistory(value.id); pendingDelete = null }, active = true) }
    } }
}
