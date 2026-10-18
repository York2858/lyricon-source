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
    var editingTrack by rememberSaveable { mutableStateOf<String?>(null) }
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
            .onSuccess { state.notify("歌词文件夹已授权，重新读取本地专辑可载入同名 LRC、TTML 或 TXT") }
            .onFailure { state.notify(it.message ?: "授权失败，请进入 Music 或具体子文件夹") }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri -> if (uri != null) state.exportText(uri) }
    Column(modifier.testTag("album-explain-page")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Action(if (history) "返回专辑" else "专辑历史", { history = !history }, enabled = !state.working, small = true)
            if (!history) {
                Action(if (editor) "收起编辑" else "编辑专辑", { editor = !editor }, enabled = !state.working, small = true)
                Action(if (state.record?.favorite == true) "已收藏" else "收藏专辑", state::favorite,
                    enabled = !state.working && state.record?.tracks?.any { it.content.isNotBlank() } == true, small = true)
            }
        }
        if (state.busy && !state.generating) Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            SmallText("正在准备专辑资料…", modifier = Modifier.weight(1f))
            Action("停止", state::stop, small = true)
        }
        if (history) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.records.isEmpty()) item { SmallText("暂无专辑解读。开始生成后会逐首保存，可停止后继续。") }
                items(state.records, key = { it.id }) { saved -> Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.surfaceContainer).padding(14.dp)) {
                    Text(saved.title, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    SmallText("${saved.artist} · ${saved.tracks.count { it.status == ExplainStatus.COMPLETE }}/${saved.tracks.size} 首已完成" + if (saved.favorite) " · 已收藏" else "")
                    Row { Action("打开专辑", { state.openHistory(saved); history = false }); Action("删除", { pendingDelete = saved }) }
                } }
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("album-track-list"), state = list,
                contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (editor) item("editor") { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("专辑与曲目", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    InputBox(state.albumTitle, { state.albumTitle = it.take(300) }, "专辑名称", singleLine = true, enabled = !state.working,
                        modifier = Modifier.testTag("album-title"))
                    InputBox(state.artist, { state.artist = it.take(300) }, "专辑艺人", singleLine = true, enabled = !state.working,
                        modifier = Modifier.testTag("album-artist"))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Action("读取本地专辑", {
                            if (ContextCompat.checkSelfPermission(context, audioPermission) == PackageManager.PERMISSION_GRANTED) state.loadLocalAlbum()
                            else permission.launch(audioPermission)
                        }, enabled = !state.working, filled = true, small = true)
                        Action("AI 补全曲目", state::suggestTracks, enabled = !state.working, filled = true, small = true)
                        Action("授权歌词文件夹", { folder.launch(TranslationExportPicker.folderIntent(context,
                            DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:Music"))) },
                            enabled = !state.working, small = true)
                    }
                    SmallText("一行一首，可带曲序；按这里的顺序逐首解读。AI 建议曲目需核对专辑版本。本地歌词仅从已授权文件夹读取。")
                    InputBox(state.trackList, { state.trackList = it.take(24000) }, "1. 曲名\n2. 曲名\n3. Holy, Holy\n4. The New Sound", minLines = 4,
                        enabled = !state.working, modifier = Modifier.heightIn(max = 240.dp).testTag("album-tracks-input"))
                    InputBox(state.referenceNotes, { state.referenceNotes = it.take(12000) }, "专辑参考资料或听感笔记（可选）", minLines = 2,
                        enabled = !state.working, modifier = Modifier.heightIn(max = 180.dp))
                    Action("应用曲目清单", { if (state.applyTracks()) editor = false }, enabled = !state.working, active = true)
                } }
                state.record?.let { record ->
                    item("summary") { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(record.title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        SmallText("${record.artist} · ${record.tracks.count { it.status == ExplainStatus.COMPLETE }}/${record.tracks.size} 首已完成")
                        SmallText(record.source)
                        SmallText("逐首分析歌词、编曲与律动。本次未输入音频；技术判断依据参考资料与作品知识，未经音频实测。")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Action(if (record.tracks.all { it.status == ExplainStatus.COMPLETE }) "整张已完成" else if (record.tracks.any { it.content.isNotBlank() }) "继续未完成" else "开始逐首解读", { state.generate() },
                                enabled = !state.working && record.tracks.any { it.status != ExplainStatus.COMPLETE }, active = true)
                            if (state.working) Action("停止生成", state::stop, active = true)
                            Action("复制整张解读", { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("专辑逐曲解读", albumPlainText(record)))) } },
                                enabled = record.tracks.any { it.content.isNotBlank() }, small = true)
                            Action("导出 TXT", {
                                val name = "${record.artist} - ${record.title}".map { if (it in "\\/:*?\"<>|" || it.isISOControl()) '_' else it }.joinToString("").take(100)
                                export.launch("$name-逐曲解读.txt")
                            }, enabled = record.tracks.any { it.content.isNotBlank() }, small = true)
                        }
                        Row(Modifier.horizontalScroll(rememberScrollState())) { record.tracks.forEachIndexed { index, track ->
                            Action("${track.number}. ${track.title}", { scope.launch { list.animateScrollToItem((if (editor) 2 else 1) + index) } }, small = true, filled = true)
                        } }
                    } }
                    itemsIndexed(record.tracks, key = { _, it -> it.id }) { _, track -> Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .background(colors.onSurface.copy(alpha = .025f)).padding(14.dp).testTag("album-track-${track.number}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("歌曲：${if (track.disc > 1) "CD ${track.disc} · " else ""}${track.number}. ${track.title}", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        SmallText(when { track.instrumental -> "纯器乐 · 重点解读编曲与结构"; track.lyrics.isNotBlank() -> "已提供歌词 · ${track.lyrics.lineSequence().count()} 行"; else -> "未提供歌词，可在资料中补充；不会虚构原句" })
                        Row { Action("歌词与编曲资料", { editingTrack = track.id }, enabled = !state.working, small = true)
                            Action(if (track.content.isNotBlank()) "重新解读本曲" else "解读本曲", { state.generate(track.id) }, enabled = !state.working, small = true) }
                        if (track.content.isNotBlank() || track.status == ExplainStatus.GENERATING)
                            TurnView(ExplainTurn(track.id, content = track.content, reasoning = track.reasoning, status = track.status), fontSize, spacing, showReasoning)
                        else SmallText(if (track.error.isNotBlank()) track.error else "尚未生成，可单独解读或开始整张专辑")
                        if (track.error.isNotBlank() && track.content.isNotBlank()) Text(track.error, color = colors.error, fontSize = 12.sp)
                    } }
                }
                if (state.record == null) item { SmallText("填写专辑和曲目后，应用清单即可逐首阅读；也可从本地媒体库读取顺序。") }
            }
        }
    }
    editingTrack?.let { id -> state.record?.tracks?.firstOrNull { it.id == id }?.let { track ->
        var lyrics by rememberSaveable(id) { mutableStateOf(track.lyrics) }
        var notes by rememberSaveable(id) { mutableStateOf(track.notes) }
        var instrumental by rememberSaveable(id) { mutableStateOf(track.instrumental) }
        WindowDialog(show = true, title = "${track.number}. ${track.title}", onDismissRequest = { editingTrack = null }) {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Text("纯器乐曲", modifier = Modifier.weight(1f)); Switch(instrumental, { instrumental = it }) }
                InputBox(lyrics, { lyrics = it.take(20000) }, "歌词原文，可粘贴整首歌词", minLines = 4, enabled = !instrumental, modifier = Modifier.heightIn(max = 220.dp))
                InputBox(notes, { notes = it.take(8000) }, "编曲资料、拍号依据、乐器或听感笔记（可选）", minLines = 3, modifier = Modifier.heightIn(max = 180.dp))
                SmallText("修改资料会清空本曲的旧输出；已收藏的整张解读会保留为独立版本。")
            }
            Row { Action("取消", { editingTrack = null }); Action("保存资料", { state.editTrack(id, lyrics, notes, instrumental); editingTrack = null }, active = true) }
        }
    } }
    pendingDelete?.let { value -> WindowDialog(show = true, title = "删除《${value.title}》的这版解读？", onDismissRequest = { pendingDelete = null }) {
        Text("会删除这张专辑的本次逐曲解读，其他版本保留。")
        Row { Action("取消", { pendingDelete = null }); Action("删除", { state.deleteHistory(value.id); pendingDelete = null }, active = true) }
    } }
}
