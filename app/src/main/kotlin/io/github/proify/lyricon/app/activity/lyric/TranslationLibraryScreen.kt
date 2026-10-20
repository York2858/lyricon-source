package io.github.proify.lyricon.app.activity.lyric

import android.content.ClipData
import android.content.Intent
import android.app.Activity
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.proify.lyricon.app.ai.translation.*
import io.github.proify.lyricon.app.R
import io.github.proify.lyricon.app.util.toast
import io.github.proify.lyricon.lyric.ai.translate.*
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Icon
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import java.text.DateFormat
import java.util.Date

private data class LibraryDeletion(val ids: List<String> = emptyList(), val groups: List<String> = emptyList(),
    val count: Int, val favorites: Int, val description: String)

@Composable
internal fun TranslationLibraryScreen(state: TranslationLibraryViewModel, onClose: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val pageStates = rememberSaveableStateHolder()
    var deletion by remember { mutableStateOf<LibraryDeletion?>(null) }
    var showVersionInfo by remember { mutableStateOf(false) }
    var folderHelp by rememberSaveable { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var exportFormat by rememberSaveable { mutableStateOf(TranslationExportFormat.TXT.name) }
    var exportContent by rememberSaveable { mutableStateOf(TranslationExportContent.BILINGUAL.name) }
    var pendingVersion by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingFormat by rememberSaveable { mutableStateOf(TranslationExportFormat.TXT.name) }
    var pendingContent by rememberSaveable { mutableStateOf(TranslationExportContent.BILINGUAL.name) }
    val exportPreferences = remember(context) { context.getSharedPreferences("translation_export_settings", android.content.Context.MODE_PRIVATE) }
    var exportDestination by rememberSaveable { mutableStateOf(exportPreferences.getString("destination", "music").orEmpty()) }
    var folderSettingsOnly by rememberSaveable { mutableStateOf(false) }
    var folderAuthError by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingFolderInitial by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingFolderKind by rememberSaveable { mutableStateOf("single") }
    var showBatchExport by rememberSaveable { mutableStateOf(false) }
    var pendingBatchSongs by rememberSaveable { mutableStateOf<String?>(null) }
    var batchFormat by rememberSaveable { mutableStateOf(TranslationExportFormat.LINE.name) }
    var batchContent by rememberSaveable { mutableStateOf(TranslationExportContent.BILINGUAL.name) }
    var batchDestination by rememberSaveable { mutableStateOf(BatchExportDestination.ZIP.name) }
    var batchDuplicates by rememberSaveable { mutableStateOf(BatchDuplicatePolicy.SKIP.name) }
    fun batchSongs() = pendingBatchSongs?.let { TranslationLibraryContract.json.decodeFromString<List<TranslationSongEntry>>(it) }.orEmpty()
    fun batchOptions() = BatchExportOptions(TranslationExportOptions(TranslationExportFormat.valueOf(batchFormat),
        TranslationExportContent.valueOf(batchContent)), BatchExportDestination.valueOf(batchDestination), BatchDuplicatePolicy.valueOf(batchDuplicates))
    fun pendingExportVersion() = pendingVersion?.let { TranslationLibraryContract.json.decodeFromString<TranslationVersion>(it) }
    fun pendingOptions() = TranslationExportOptions(TranslationExportFormat.valueOf(pendingFormat), TranslationExportContent.valueOf(pendingContent))
    val musicFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            runCatching { TranslationExportPicker.takeFolderGrant(context, result.resultCode, result.data) }.onSuccess { uri ->
                when (pendingFolderKind) {
                    "batch-folder" -> {
                        exportPreferences.edit().putString("batchFolder", uri.toString()).apply()
                        state.exportBatch(batchSongs(), batchOptions(), uri)
                    }
                    "batch-authorize" -> state.rememberBatchMusicGrant(uri)
                    else -> pendingExportVersion()?.let { version ->
                        if (folderSettingsOnly) state.rememberMusicFolder(version, uri)
                        else state.exportToMusicFolder(version, pendingOptions(), selectedFolder = uri)
                    }
                }
                pendingVersion = null; folderSettingsOnly = false; pendingFolderKind = "single"
            }.onFailure { folderAuthError = it.message ?: "未获得文件夹授权，请重试" }
        } else {
            if (pendingFolderKind == "batch-folder") showBatchExport = true
            pendingVersion = null; folderSettingsOnly = false; pendingFolderKind = "single"
        }
    }
    fun launchFolderPicker(initial: android.net.Uri?, preferSystem: Boolean = true) {
        pendingFolderInitial = initial?.toString()
        runCatching { musicFolderPicker.launch(TranslationExportPicker.folderIntent(context, initial, preferSystem)) }
            .onFailure { folderAuthError = "无法打开文件夹授权窗口，请尝试其他选择器" }
    }
    val batchZipPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) state.exportBatch(batchSongs(), batchOptions(), uri)
        else showBatchExport = true
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) pendingVersion?.let {
            state.export(uri, TranslationLibraryContract.json.decodeFromString<TranslationVersion>(it),
                TranslationExportOptions(TranslationExportFormat.valueOf(pendingFormat), TranslationExportContent.valueOf(pendingContent)))
        }
        pendingVersion = null
    }
    var lineSearch by rememberSaveable(state.version?.id) { mutableStateOf("") }
    var readingMode by rememberSaveable { mutableStateOf("双语对照") }
    val displayedMode = if (state.version?.hasOriginal == false && readingMode == "只看原文") "只看译文" else readingMode
    var fontSize by rememberSaveable { mutableIntStateOf(17) }
    fun back() { if (!state.back()) onClose() }
    BackHandler { back() }
    LaunchedEffect(state.notice) { state.notice?.let { toast(it); state.clearNotice() } }

    Column(Modifier.fillMaxSize().background(colors.surface).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            LibraryButton("返回", onClick = ::back)
            Text(if (state.version != null && state.version?.status != "translated") "处理详情" else if (state.version != null) "双语歌词" else if (state.group != null) "翻译版本" else "翻译库",
                fontSize = 21.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
            LibraryButton("刷新", enabled = !state.loading && !state.busy, onClick = state::refresh)
        }
        if (state.group == null) {
            LibrarySearch(state.query.search, "歌名 / 歌手 / 歌词") { state.updateQuery(state.query.copy(search = it)) }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LibraryButton("全部", selected = state.query.status == "translated" && !state.query.favoritesOnly,
                    onClick = { state.updateQuery(state.query.copy(status = "translated", favoritesOnly = false)) })
                LibraryButton("收藏", selected = state.query.status == "translated" && state.query.favoritesOnly,
                    onClick = { state.updateQuery(state.query.copy(status = "translated", favoritesOnly = true)) })
                LibraryButton("跳过翻译", selected = state.query.status == "skipped",
                    onClick = { state.updateQuery(state.query.copy(status = "skipped", favoritesOnly = false)) })
                LibraryButton("翻译报错", selected = state.query.status == "error",
                    onClick = { state.updateQuery(state.query.copy(status = "error", favoritesOnly = false)) })
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LibraryChoiceMenu(state.query.language.ifBlank { "所有语言" }, state.query.language,
                    listOf("" to "所有语言") + state.page?.languages.orEmpty().map { it to it }) {
                    state.updateQuery(state.query.copy(language = it))
                }
                LibraryChoiceMenu(when (state.query.sort) { "oldest" -> "最早保存"; "title" -> "歌曲名称"; else -> "最近保存" },
                    state.query.sort, listOf("newest" to "最近保存", "oldest" to "最早保存", "title" to "歌曲名称")) {
                    state.updateQuery(state.query.copy(sort = it))
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (state.selecting) "已选 ${state.selected.size} 首" else "${state.page?.totalSongs ?: 0} 首", modifier = Modifier.weight(1f), fontSize = 13.sp)
                if (state.selecting) {
                    LibraryButton("全选结果", enabled = !state.busy, onClick = state::selectAllResults)
                    LibraryButton("取消", enabled = !state.busy, onClick = state::endSelection)
                } else LibraryButton("批量管理", enabled = state.songs.isNotEmpty(), onClick = { state.selecting = true })
            }
            if (state.selecting) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LibraryButton("批量导出", enabled = state.selected.isNotEmpty() && !state.busy && state.query.status !in setOf("skipped", "error"), onClick = {
                        pendingBatchSongs = TranslationLibraryContract.json.encodeToString(state.selectedBatchSongs())
                        showBatchExport = true
                    })
                    LibraryButton("删除", enabled = state.selected.isNotEmpty() && !state.busy, onClick = {
                        val rows = state.songs.filter { it.key in state.selected }
                        deletion = LibraryDeletion(groups = rows.map { it.key }, count = rows.sumOf { it.versions },
                            favorites = rows.sumOf { it.favorites }, description = "所选 ${rows.size} 首歌曲的所有翻译版本")
                    })
            }
        } else if (state.version == null) {
            val song = state.group!!
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(song.title.ifBlank { "未知歌曲（旧记录）" }, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                Text(song.artist.ifBlank { "歌手未记录" }, fontSize = 14.sp, color = colors.onSurfaceVariantSummary)
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    LibraryButton("删除整首", enabled = !state.busy, onClick = {
                        deletion = LibraryDeletion(groups = listOf(song.key), count = song.versions, favorites = song.favorites,
                            description = "《${song.title.ifBlank { "未知歌曲" }}》的所有翻译版本")
                    })
                }
            }
        } else if (state.version?.status != "translated") {
            val version = state.version!!
            Text(version.title.ifBlank { "未知歌曲" }, fontSize = 21.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            Text(version.artist, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp))
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LibraryButton("复制原因", onClick = { scope.launch {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("翻译处理详情",
                        listOf(version.title, version.reason, version.suggestion, version.resolution).filter { it.isNotBlank() }.joinToString("\n"))))
                    toast("已复制处理详情")
                } })
                LibraryButton("删除记录", enabled = !state.busy, onClick = {
                    deletion = LibraryDeletion(ids = listOf(version.id), count = 1, favorites = 0, description = "当前处理记录")
                })
            }
        } else {
            val version = state.version!!
            Text(version.title.ifBlank { "未知歌曲（旧记录）" }, fontSize = 21.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            Text(listOf(version.artist, version.language.ifBlank { "语言未记录" }).filter { it.isNotBlank() }.joinToString(" · "),
                fontSize = 12.sp, color = colors.onSurfaceVariantSummary, modifier = Modifier.padding(horizontal = 20.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LibraryButton(if (version.favorite) "已收藏" else "收藏", selected = version.favorite, enabled = !state.busy, onClick = { state.favorite(version) })
                LibraryButton("复制全文", enabled = !state.busy, onClick = { state.copy { text -> scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("双语歌词", text))) } } })
                LibraryButton("导出", enabled = !state.busy && !state.loading && state.error == null && state.lines.isNotEmpty(), onClick = { showExport = true })
                LibraryButton("分享", enabled = !state.busy, onClick = {
                    state.copy(noticeText = "已准备分享内容") { text -> context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text)
                    }, "分享双语歌词")) }
                })
                LibraryButton("删除", enabled = !state.busy, onClick = {
                    deletion = LibraryDeletion(ids = listOf(version.id), count = 1, favorites = if (version.favorite) 1 else 0, description = "当前翻译版本")
                })
            }
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!version.hasOriginal) Text("仅有译文", fontSize = 12.sp, color = colors.onSurfaceVariantSummary)
                LibraryButton("版本信息", onClick = { showVersionInfo = true })
            }
            LibrarySearch(lineSearch, "查找歌词") { lineSearch = it }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("双语对照", "只看译文", "只看原文").forEach { mode ->
                    LibraryButton(when (mode) { "双语对照" -> "双语"; "只看译文" -> "译文"; else -> "原文" }, selected = mode == displayedMode, enabled = mode != "只看原文" || version.hasOriginal, onClick = { readingMode = mode })
                }
                LibraryButton("字号 −", enabled = fontSize > 13, onClick = { fontSize-- })
                LibraryButton("字号 +", enabled = fontSize < 26, onClick = { fontSize++ })
            }
        }
        val listKey = state.version?.id ?: state.group?.key ?: "songs"
        pageStates.SaveableStateProvider(listKey) {
            val listState = rememberLazyListState()
            Column(Modifier.weight(1f)) {
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = if (state.version != null) 4.dp else 10.dp),
                    verticalArrangement = Arrangement.spacedBy(if (state.version != null) 4.dp else 10.dp)) {
                    if (state.version != null && state.version?.status != "translated") {
                        val record = state.version!!
                        item { LibraryCard {
                            Text(if (record.status == "skipped" && record.resolution.startsWith("已收到")) "已收到歌词" else if (record.status == "skipped") "跳过翻译" else "翻译报错", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Text("原因", fontSize = 13.sp, color = colors.onSurfaceVariantSummary)
                            SelectionContainer { Text(record.reason.ifBlank { "旧版本未记录原因" }, fontSize = 16.sp, lineHeight = 24.sp) }
                            if (record.suggestion.isNotBlank()) {
                                Text("处理建议", fontSize = 13.sp, color = colors.onSurfaceVariantSummary)
                                SelectionContainer { Text(record.suggestion, fontSize = 15.sp, lineHeight = 23.sp) }
                            }
                            if (record.resolution.isNotBlank()) {
                                Text(if (record.status == "skipped") "后续状态" else "保底结果", fontSize = 13.sp, color = colors.onSurfaceVariantSummary)
                                Text(record.resolution, fontSize = 15.sp, color = colors.primary)
                            }
                            Text("最近发生：${libraryDate(record.createdAt)} · 共 ${record.eventCount} 次", fontSize = 12.sp, color = colors.onSurfaceVariantSummary)
                            Text(listOf(record.language, record.model).filter { it.isNotBlank() }.joinToString(" · "), fontSize = 12.sp, color = colors.onSurfaceVariantSummary)
                        } }
                    } else if (state.version != null) {
                        val filtered = state.lines.filter { lineSearch.isBlank() || it.original.contains(lineSearch, true) || it.translation.contains(lineSearch, true) }
                        items(filtered, key = { it.index }) { line ->
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.surfaceContainer)
                                .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                SelectionContainer(Modifier.weight(1f)) { Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    if (displayedMode != "只看译文" && line.original.isNotBlank()) Text(line.original, fontSize = fontSize.sp, lineHeight = (fontSize * 1.4).sp,
                                        color = if (displayedMode == "只看原文") colors.onSurface else colors.onSurfaceVariantSummary)
                                    if (displayedMode != "只看原文") Text(line.translation, fontSize = fontSize.sp, lineHeight = (fontSize * 1.4).sp)
                                } }
                                Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button) {
                                    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("歌词", listOf(line.original, line.translation).filter { it.isNotBlank() }.joinToString("\n")))); toast("已复制本句") }
                                }.semantics { contentDescription = "复制第 ${line.index + 1} 句" }, contentAlignment = Alignment.Center) {
                                    Icon(painterResource(R.drawable.ic_copy), null, Modifier.size(17.dp), tint = colors.onSurfaceVariantSummary)
                                }
                            }
                        }
                        if (filtered.isEmpty() && !state.loading && state.error == null) item { LibraryEmpty("没有匹配的歌词", "试试其他原文或译文关键词") }
                    } else if (state.group != null) {
                        items(state.versions, key = { it.id }) { version ->
                            LibraryCard(onClick = { state.openVersion(version) }) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(when (version.status) { "skipped" -> if (version.resolution.startsWith("已收到")) "已收到歌词" else "跳过翻译"; "error" -> "翻译报错"; else -> version.language.ifBlank { "语言未记录（旧记录）" } }, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                    LibraryButton(if (version.favorite) "已收藏" else "收藏", selected = version.favorite, enabled = !state.busy && version.status == "translated", onClick = { state.favorite(version) })
                                }
                                if (version.status != "translated") {
                                    Text(version.reason, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                    if (version.resolution.isNotBlank()) Text(version.resolution, fontSize = 12.sp, color = colors.primary)
                                }
                                Text(if (version.source == "google") "Google 翻译（保底）" else version.model.ifBlank { "模型未记录" }, fontSize = 13.sp, color = colors.onSurfaceVariantSummary)
                                Text(libraryDate(version.createdAt), fontSize = 12.sp, color = colors.onSurfaceVariantSummary)
                            }
                        }
                        if (state.versions.isEmpty() && !state.loading && state.error == null) item { LibraryEmpty("暂无翻译版本", "记录可能已删除，返回列表刷新即可") }
                    } else {
                        items(state.songs, key = { it.key }) { song ->
                            LibraryCard(onClick = { if (state.selecting) state.toggle(song.key) else state.openSong(song) }) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    if (state.selecting) Text(if (song.key in state.selected) "☑" else "☐", color = colors.primary, fontSize = 22.sp, modifier = Modifier.padding(end = 12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(song.title.ifBlank { "未知歌曲（旧记录）" }, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        if (state.query.status in setOf("skipped", "error") && (song.skipped > 0 || song.errors > 0)) Text("翻译 ${song.translated} · 跳过 ${song.skipped} · 报错 ${song.errors}", fontSize = 12.sp, color = colors.primary)
                                        Text("${song.artist.ifBlank { "歌手未记录" }} · ${song.languages.ifBlank { "语言未记录" }} · ${song.versions} 条记录", fontSize = 12.sp, color = colors.onSurfaceVariantSummary)
                                    }
                                    if (song.favorites > 0) Text("★ ${song.favorites}", fontSize = 13.sp, color = colors.primary)
                                }
                            }
                        }
                        if (state.songs.isEmpty() && !state.loading && state.error == null) item { LibraryEmpty(
                            if (state.query.search.isNotBlank() || state.query.language.isNotBlank() || state.query.favoritesOnly) "没有符合条件的歌曲" else "还没有翻译记录",
                            if (state.query.search.isNotBlank() || state.query.language.isNotBlank() || state.query.favoritesOnly) "可以更换关键词，或切回全部语言与全部记录" else "播放歌曲后会记录翻译结果，以及跳过或失败的具体原因") }
                    }
                    if (state.error != null) item { LibraryCard {
                        Text(state.error!!, fontSize = 14.sp)
                        LibraryButton("重试", onClick = state::refresh)
                    } }
                    if (state.loading || state.busy) item { Text(if (state.busy) "正在处理…" else "正在加载…", fontSize = 14.sp, modifier = Modifier.padding(12.dp)) }
                    val more = if (state.version != null) false else if (state.group != null) state.moreVersions else state.page?.hasMore == true
                    if (more && !state.loading) item { LibraryButton("加载更多", onClick = state::more) }
                }
                if (state.version != null && state.version?.status == "translated") Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    LibraryButton("回到开头", onClick = { scope.launch { listState.animateScrollToItem(0) } })
                    LibraryButton("跳到末尾", onClick = { scope.launch { listState.animateScrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)) } })
                }
            }
        }
    }
    if (showExport && state.version != null) WindowDialog(show = true, title = "导出歌词", onDismissRequest = { showExport = false }) {
        val version = state.version!!
        val originalAvailable = state.lines.isNotEmpty() && state.lines.all { it.original.isNotBlank() }
        val timingAvailable = state.lines.isNotEmpty() && state.lines.all {
            val begin = it.begin; val end = it.end
            begin != null && end != null && begin >= 0 && end > begin
        }
        val content = if (!originalAvailable) TranslationExportContent.TRANSLATION else TranslationExportContent.valueOf(exportContent)
        val savedFolder = remember(version.id, state.musicFolderRevision, state.musicLocation) { state.savedMusicFolder(version) }
        LaunchedEffect(version.id) { state.locateMusicFolder(version) }
        val detectedFolder = state.detectedMusicFolder()
        val detectedGranted = detectedFolder?.let(state::hasMusicFolderAccess) == true
        LazyColumn(Modifier.heightIn(max = 520.dp).testTag("translation-export-options"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("内容", fontSize = 13.sp, color = colors.onSurfaceVariantSummary) }
            item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TranslationExportContent.entries.forEach { choice -> LibraryButton(choice.label,
                    selected = choice == content, enabled = originalAvailable || choice == TranslationExportContent.TRANSLATION,
                    onClick = { exportContent = choice.name }) }
            } }
            item { Text("保存位置", fontSize = 13.sp, color = colors.onSurfaceVariantSummary) }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("music" to "音乐所在文件夹", "choose" to "另存为").forEach { (key, label) ->
                    LibraryButton(label, selected = exportDestination == key, onClick = {
                        exportDestination = key; exportPreferences.edit().putString("destination", key).apply()
                    })
                }
            } }
            if (exportDestination == "music") item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(savedFolder?.let { "保存到：${it.name}" }
                        ?: detectedFolder?.let { "已识别：${it.name}" + if (detectedGranted) "\n可直接保存" else "\n首次保存需授权此文件夹" }
                        ?: if (state.locatingMusic) "正在查找音乐所在文件夹…"
                        else state.musicLocation?.message ?: "自动查找音乐所在文件夹", fontSize = 12.sp,
                        color = colors.onSurfaceVariantSummary, modifier = Modifier.weight(1f))
                    LibraryButton(if (savedFolder != null || detectedGranted) "更换文件夹" else if (detectedFolder != null) "授权文件夹" else "选择文件夹", enabled = !state.busy && !state.locatingMusic, onClick = {
                        pendingVersion = TranslationLibraryContract.json.encodeToString(version)
                        folderSettingsOnly = true
                        pendingFolderKind = "single"
                        state.chooseMusicFolder(version) { launchFolderPicker(it) }
                    })
                }
            }
            if (exportDestination == "music") item {
                LibraryButton("无法授权文件夹？", onClick = { folderHelp = !folderHelp })
                if (folderHelp) Text("请进入 Music 或具体子文件夹。内部存储根目录、Download 根目录可能禁止授权；也可选择另存为。",
                    fontSize = 12.sp, color = colors.onSurfaceVariantSummary)
            }
            item {
                Text(translationExportFileName(version, TranslationExportOptions(TranslationExportFormat.valueOf(exportFormat), content),
                    state.musicLocation?.audioFileName), fontSize = 12.sp, color = colors.onSurfaceVariantSummary)
            }
            if (!timingAvailable) item { Text("缺少时间轴，可导出 TXT。再次播放歌曲可补齐。",
                fontSize = 12.sp, color = colors.onSurfaceVariantSummary) }
            item { Text("格式", fontSize = 13.sp, color = colors.onSurfaceVariantSummary) }
            items(TranslationExportFormat.entries) { format ->
                LibraryButton(format.label, selected = exportFormat == format.name,
                    enabled = !state.busy && (format == TranslationExportFormat.TXT || timingAvailable),
                    onClick = { exportFormat = format.name })
            }
            item {
                val format = TranslationExportFormat.valueOf(exportFormat)
                LibraryButton("导出", enabled = !state.busy && (format == TranslationExportFormat.TXT || timingAvailable), onClick = {
                    pendingVersion = TranslationLibraryContract.json.encodeToString(version)
                    pendingFormat = format.name; pendingContent = content.name
                    val options = TranslationExportOptions(format, content)
                    showExport = false
                    if (exportDestination == "music") {
                        folderSettingsOnly = false
                        state.exportToMusicFolder(version, options, onChooseFolder = { initial ->
                            pendingFolderKind = "single"; launchFolderPicker(initial)
                        })
                    } else state.prepareSaveAs(version, options) { fileName ->
                        export.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE); type = format.mimeType
                            putExtra(Intent.EXTRA_TITLE, fileName)
                        })
                    }
                })
            }
        }
    }
    if (showBatchExport) WindowDialog(show = true, title = "批量导出歌词", onDismissRequest = { showBatchExport = false }) {
        LazyColumn(Modifier.heightIn(max = 500.dp).testTag("translation-batch-options"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("已选 ${batchSongs().size} 首 · 每首导出最新版本。", fontSize = 14.sp) }
            item { Text("格式", fontSize = 13.sp, color = colors.onSurfaceVariantSummary) }
            item { LibraryChoiceMenu(TranslationExportFormat.valueOf(batchFormat).label, batchFormat,
                TranslationExportFormat.entries.map { it.name to it.label }) { batchFormat = it } }
            item { Text("内容", fontSize = 13.sp, color = colors.onSurfaceVariantSummary) }
            item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TranslationExportContent.entries.forEach { content -> LibraryButton(content.label,
                    selected = content.name == batchContent, onClick = { batchContent = content.name }) }
            } }
            item { Text("保存位置", fontSize = 13.sp, color = colors.onSurfaceVariantSummary) }
            item { LibraryChoiceMenu(BatchExportDestination.valueOf(batchDestination).label, batchDestination,
                BatchExportDestination.entries.map { it.name to it.label }) { batchDestination = it } }
            if (batchDestination != BatchExportDestination.ZIP.name) {
                item { Text("同名文件", fontSize = 13.sp, color = colors.onSurfaceVariantSummary) }
                item { LibraryChoiceMenu(BatchDuplicatePolicy.valueOf(batchDuplicates).label, batchDuplicates,
                    BatchDuplicatePolicy.entries.map { it.name to it.label }) { batchDuplicates = it } }
            }
            if (batchDestination == BatchExportDestination.MUSIC.name) item {
                LibraryButton("授权音乐文件夹", enabled = !state.busy, onClick = {
                    pendingFolderKind = "batch-authorize"
                    launchFolderPicker(DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:Music"))
                })
            }
            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LibraryButton("取消", onClick = { showBatchExport = false })
                LibraryButton("开始导出", enabled = !state.busy && batchSongs().isNotEmpty(), onClick = {
                    val options = batchOptions(); showBatchExport = false
                    when (options.destination) {
                        BatchExportDestination.MUSIC -> state.exportBatch(batchSongs(), options)
                        BatchExportDestination.FOLDER -> {
                            pendingFolderKind = "batch-folder"
                            launchFolderPicker(exportPreferences.getString("batchFolder", null)?.let(android.net.Uri::parse))
                        }
                        BatchExportDestination.ZIP -> {
                            val time = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(Date())
                            batchZipPicker.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE); type = "application/zip"
                                putExtra(Intent.EXTRA_TITLE, "歌词批量导出-$time.zip")
                            })
                        }
                    }
                })
            } }
        }
    }
    if (showVersionInfo && state.version != null) WindowDialog(show = true, title = "版本信息", onDismissRequest = { showVersionInfo = false }) {
        val version = state.version!!
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (version.source == "google") "Google 翻译（AI 失败后的保底结果）" else version.model.ifBlank { "模型未记录" })
            Text(libraryDate(version.createdAt), fontSize = 13.sp)
            Text("${version.lineCount} 句", fontSize = 13.sp)
            if (!version.hasOriginal) Text("再次播放歌曲可补全原文与歌曲信息。", fontSize = 13.sp)
            LibraryButton("知道了", onClick = { showVersionInfo = false })
        }
    }
    folderAuthError?.let { message -> WindowDialog(show = true, title = "文件夹授权未完成", onDismissRequest = { folderAuthError = null }) {
        Text(message, fontSize = 14.sp)
        Text("请进入 Music 或具体子文件夹。若当前目录的按钮灰色，进入下一层目录；也可改用另存为或批量 ZIP 导出。", fontSize = 12.sp,
            color = colors.onSurfaceVariantSummary, modifier = Modifier.padding(top = 12.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LibraryButton("取消", onClick = { folderAuthError = null; pendingVersion = null; folderSettingsOnly = false; pendingFolderKind = "single" })
            LibraryButton("系统窗口重试", onClick = { folderAuthError = null; launchFolderPicker(pendingFolderInitial?.let(android.net.Uri::parse)) })
            LibraryButton("其他选择器", onClick = { folderAuthError = null; launchFolderPicker(pendingFolderInitial?.let(android.net.Uri::parse), false) })
        }
    } }
    state.batchProgress?.let { progress -> WindowDialog(show = true, title = "正在批量导出", onDismissRequest = {}) {
        Text("${progress.completed} / ${progress.total} 首", fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Text(progress.song, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp))
        Text("停止后保留已导出的文件，当前歌曲处理完成后停止。", fontSize = 12.sp, color = colors.onSurfaceVariantSummary,
            modifier = Modifier.padding(top = 12.dp))
        LibraryButton("停止导出", onClick = state::stopBatchExport)
    } }
    state.batchResult?.let { result -> WindowDialog(show = true, title = if (result.cancelled) "批量导出已停止" else "批量导出结果", onDismissRequest = state::dismissBatchResult) {
        Text("成功 ${result.succeeded} · 跳过 ${result.skipped} · 失败 ${result.failed}" +
            if (result.items.size < result.total) " · 未处理 ${result.total - result.items.size}" else "", fontSize = 14.sp)
        LazyColumn(Modifier.heightIn(max = 380.dp).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(result.items) { item -> Column {
                Text("${if (item.success) "✓" else if (item.skipped) "—" else "!"} ${item.song}", fontSize = 14.sp)
                Text(item.detail, fontSize = 12.sp, color = colors.onSurfaceVariantSummary)
            } }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LibraryButton("复制结果", onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("歌词导出结果",
                result.items.joinToString("\n") { "${it.song}：${it.detail}" }))) } })
            LibraryButton("完成", onClick = state::dismissBatchResult)
        }
    } }
    deletion?.let { pending -> WindowDialog(show = true, title = "删除翻译记录？", onDismissRequest = { deletion = null }) {
        Text("将删除${pending.description}，共 ${pending.count} 条记录，其中 ${pending.favorites} 条已收藏。删除后无法恢复；再次播放时可能重新生成翻译。", fontSize = 15.sp)
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
            LibraryButton("取消", onClick = { deletion = null })
            LibraryButton("确认删除", enabled = !state.busy, onClick = { deletion = null; state.delete(pending.ids, pending.groups) })
        }
    } }
    state.musicOverwrite?.let { pending -> WindowDialog(show = true, title = "已存在同名歌词", onDismissRequest = state::dismissMusicOverwrite) {
        Text("${pending.folder.name}/${pending.name}\n\n请选择覆盖原文件，或保留原文件并另存一份。", fontSize = 14.sp)
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LibraryButton("取消", enabled = !state.busy, onClick = state::dismissMusicOverwrite)
            LibraryButton("另存一份", enabled = !state.busy, onClick = { state.confirmMusicOverwrite(copy = true) })
            LibraryButton("覆盖", enabled = !state.busy, onClick = { state.confirmMusicOverwrite(copy = false) })
        }
    } }
}

@Composable
private fun LibraryChoiceMenu(label: String, selected: String, choices: List<Pair<String, String>>, onChoose: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val colors = MiuixTheme.colorScheme
    Box {
        LibraryButton(label, onClick = { expanded = true })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false },
            containerColor = colors.surfaceContainer, shape = RoundedCornerShape(16.dp)) {
            choices.forEach { (key, text) -> DropdownMenuItem(
                text = { Text(text, color = if (key == selected) colors.primary else colors.onSurface, fontSize = 14.sp) },
                trailingIcon = { if (key == selected) Text("✓", color = colors.primary) },
                onClick = { expanded = false; onChoose(key) }) }
        }
    }
}

@Composable
private fun LibrarySearch(value: String, hint: String, onChange: (String) -> Unit) {
    val colors = MiuixTheme.colorScheme
    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
        .background(colors.onSurface.copy(alpha = .06f)).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(value, onChange, singleLine = true, modifier = Modifier.weight(1f).padding(vertical = 6.dp),
            textStyle = androidx.compose.ui.text.TextStyle(color = colors.onSurface, fontSize = 15.sp), cursorBrush = SolidColor(colors.primary),
            decorationBox = { inner -> Box { if (value.isEmpty()) Text(hint, fontSize = 14.sp, color = colors.onSurfaceVariantSummary); inner() } })
        if (value.isNotEmpty()) LibraryButton("清除", onClick = { onChange("") })
    }
}

@Composable
private fun LibraryButton(text: String, enabled: Boolean = true, selected: Boolean = false, onClick: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    Box(Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp))
        .background(if (selected) colors.primary.copy(alpha = .12f) else colors.onSurface.copy(alpha = .04f))
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 13.sp, color = (if (selected) colors.primary else colors.onSurface).copy(alpha = if (enabled) 1f else .38f))
    }
}

@Composable
private fun LibraryCard(onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MiuixTheme.colorScheme.surfaceContainer)
        .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
        .padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
private fun LibraryEmpty(title: String, description: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        Text(description, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

private fun libraryDate(time: Long): String = if (time <= 0) "时间未记录" else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(time))
