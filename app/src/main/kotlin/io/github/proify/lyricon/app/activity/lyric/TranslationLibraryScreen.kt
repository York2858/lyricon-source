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
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import java.text.DateFormat
import java.util.Date
import java.util.Calendar

private data class LibraryDeletion(val ids: List<String> = emptyList(), val groups: List<String> = emptyList(),
    val count: Int, val favorites: Int, val description: String)
private enum class LibraryButtonTone { NORMAL, PRIMARY, EXPORT, DANGER }

@Composable
internal fun TranslationLibraryScreen(state: TranslationLibraryViewModel, onClose: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val pageStates = rememberSaveableStateHolder()
    var deletion by remember { mutableStateOf<LibraryDeletion?>(null) }
    var showVersionInfo by remember { mutableStateOf(false) }
    var showTimeFilter by rememberSaveable { mutableStateOf(false) }
    var folderHelp by rememberSaveable { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var exportFormat by rememberSaveable { mutableStateOf(TranslationExportFormat.ENHANCED.name) }
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
    var batchFormat by rememberSaveable { mutableStateOf(TranslationExportFormat.ENHANCED.name) }
    var batchContent by rememberSaveable { mutableStateOf(TranslationExportContent.BILINGUAL.name) }
    var batchDestination by rememberSaveable { mutableStateOf(BatchExportDestination.MUSIC.name) }
    var batchDuplicates by rememberSaveable { mutableStateOf(BatchDuplicatePolicy.SKIP.name) }
    fun batchSongs() = pendingBatchSongs?.let { TranslationLibraryContract.json.decodeFromString<List<TranslationSongEntry>>(it) }.orEmpty()
    fun batchOptions() = BatchExportOptions(TranslationExportOptions(if (batchFormat == "AUTO") TranslationExportFormat.LINE else TranslationExportFormat.valueOf(batchFormat),
        TranslationExportContent.valueOf(batchContent)), BatchExportDestination.valueOf(batchDestination), BatchDuplicatePolicy.valueOf(batchDuplicates),
        autoFormat = batchFormat == "AUTO")
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
    LaunchedEffect(state.notice) { state.notice?.let { toast(it); state.clearNotice() } }

    var showMore by rememberSaveable { mutableStateOf(false) }
    var showVersions by rememberSaveable { mutableStateOf(false) }
    var showReadingOptions by rememberSaveable { mutableStateOf(false) }
    var showLineSearch by rememberSaveable { mutableStateOf(false) }
    var directReader by rememberSaveable { mutableStateOf(false) }
    fun back() {
        if (state.version?.status == "translated" && directReader) {
            state.back(); state.back(); directReader = false
        } else if (!state.back()) onClose()
    }
    BackHandler { back() }
    val listStates = remember { mutableMapOf<String, androidx.compose.foundation.lazy.LazyListState>() }
    fun prepareBatchExport() {
        pendingBatchSongs = TranslationLibraryContract.json.encodeToString(state.selectedBatchSongs())
        showBatchExport = true
    }
    fun deleteSelection() {
        val rows = state.songs.filter { it.key in state.selected }
        deletion = LibraryDeletion(groups = rows.map { it.key }, count = rows.sumOf { it.versions },
            favorites = rows.sumOf { it.favorites }, description = "所选 ${rows.size} 首歌曲的所有翻译版本")
    }
    val reading = state.version?.status == "translated"
    Column(Modifier.fillMaxSize().background(colors.surface).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            LibraryIconAction("返回", R.drawable.keyboard_arrow_right_24px, rotation = 180f, onClick = ::back)
            Text(when { reading -> "双语歌词"; state.version != null -> "处理详情"; state.group != null -> "翻译版本";
                state.selecting -> "已选 ${state.selected.size} 首"; else -> "翻译库" },
                fontSize = if (reading) 17.sp else 23.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(start = 4.dp))
            if (state.group == null) LibraryTextAction(if (state.selecting) "完成" else "选择", enabled = !state.busy,
                selected = true, onClick = { if (state.selecting) state.endSelection() else state.selecting = true })
            if (reading) state.version?.let { version ->
                LibraryIconAction(if (version.favorite) "已收藏" else "收藏", if (version.favorite) R.drawable.library_star_filled else R.drawable.library_star, enabled = !state.busy,
                    tint = if (version.favorite) colors.primary else colors.onSurfaceVariantSummary, onClick = { state.favorite(version) })
            }
            LibraryIconAction("更多操作", R.drawable.more_horiz_24px, onClick = { showMore = true })
        }
        if (state.group == null) {
            LibrarySearch(state.query.search, "搜索歌曲、歌手或歌词") { state.updateQuery(state.query.copy(search = it)) }
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LibraryTab("全部", state.query.status == "translated" && !state.query.favoritesOnly) { state.updateQuery(state.query.copy(status = "translated", favoritesOnly = false)) }
                LibraryTab("收藏", state.query.status == "translated" && state.query.favoritesOnly) { state.updateQuery(state.query.copy(status = "translated", favoritesOnly = true)) }
                LibraryTab("跳过", state.query.status == "skipped") { state.updateQuery(state.query.copy(status = "skipped", favoritesOnly = false)) }
                LibraryTab("报错", state.query.status == "error") { state.updateQuery(state.query.copy(status = "error", favoritesOnly = false)) }
            }
            androidx.compose.material3.HorizontalDivider(color = colors.onSurface.copy(alpha = .06f))
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${state.page?.totalSongs ?: 0} 首歌曲", modifier = Modifier.weight(1f), fontSize = 12.sp, color = colors.onSurfaceVariantSummary)
                if (state.selecting) LibraryTextAction("全选结果", enabled = !state.busy, selected = true, onClick = state::selectAllResults)
                else {
                    val filterCount = listOf(state.query.language.isNotBlank(), state.query.updatedFrom != null || state.query.updatedBefore != null).count { it }
                    LibraryTextAction(if (filterCount == 0) "筛选" else "筛选 · $filterCount", selected = filterCount > 0,
                        modifier = Modifier.testTag("translation-filter-action"), onClick = { showTimeFilter = true })
                    LibraryChoiceMenu(when (state.query.sort) { "oldest" -> "最早翻译"; "title" -> "歌曲名称"; else -> "最近翻译" }, state.query.sort,
                        listOf("newest" to "最近翻译", "oldest" to "最早翻译", "title" to "歌曲名称"), emphasized = false) { state.updateQuery(state.query.copy(sort = it)) }
                }
            }
            if (state.query.language.isNotBlank() || state.query.updatedFrom != null || state.query.updatedBefore != null) {
                Text(listOf(state.query.language.takeIf { it.isNotBlank() },
                    if (state.query.updatedFrom != null || state.query.updatedBefore != null) libraryTimeRange(state.query) else null).filterNotNull().joinToString(" · "),
                    fontSize = 12.sp, color = colors.primary, modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 4.dp))
            }
        } else if (state.version == null) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
                Text(state.group!!.title.ifBlank { "未知歌曲（旧记录）" }, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(state.group!!.artist.ifBlank { "歌手未记录" }, fontSize = 14.sp, color = colors.onSurfaceVariantSummary)
            }
        } else {
            val version = state.version!!
            Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 10.dp)) {
                Text(version.title.ifBlank { "未知歌曲（旧记录）" }, fontSize = 27.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOf(version.artist, version.language.ifBlank { "语言未记录" }).filter { it.isNotBlank() }.joinToString(" · "),
                    fontSize = 13.sp, color = colors.onSurfaceVariantSummary, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
                if (reading) LibraryButton("${when { version.hasWordTiming -> "逐字"; version.hasTiming -> "逐行"; else -> "未检测" }} · ${libraryDate(version.createdAt)} ⌄",
                    selected = true, onClick = { showVersions = true })
            }
            if (reading && showLineSearch) LibrarySearch(lineSearch, "查找歌词") { lineSearch = it }
        }
        val listKey = state.version?.id ?: state.group?.key ?: "songs"
        pageStates.SaveableStateProvider(listKey) {
            val listState = rememberLazyListState()
            SideEffect { listStates[listKey] = listState }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("translation-library-list"), state = listState,
                contentPadding = PaddingValues(horizontal = if (reading) 24.dp else 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(if (reading) 8.dp else 0.dp)) {
                if (state.version != null && !reading) {
                    val record = state.version!!
                    item { LibraryCard {
                        Text(if (record.status == "skipped" && record.resolution.startsWith("已收到")) "已收到歌词" else if (record.status == "skipped") "跳过翻译" else "翻译报错", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        Text("原因", fontSize = 13.sp, color = colors.onSurfaceVariantSummary)
                        SelectionContainer { Text(record.reason.ifBlank { "旧版本未记录原因" }, fontSize = 16.sp, lineHeight = 25.sp) }
                        if (record.suggestion.isNotBlank()) {
                            Text("处理建议", fontSize = 13.sp, color = colors.onSurfaceVariantSummary)
                            SelectionContainer { Text(record.suggestion, fontSize = 15.sp, lineHeight = 24.sp) }
                        }
                        if (record.resolution.isNotBlank()) {
                            Text(if (record.status == "skipped") "后续状态" else "保底结果", fontSize = 13.sp, color = colors.onSurfaceVariantSummary)
                            Text(record.resolution, fontSize = 15.sp, color = colors.primary)
                        }
                        Text("最近发生：${libraryDate(record.createdAt)} · 共 ${record.eventCount} 次", fontSize = 12.sp, color = colors.onSurfaceVariantSummary)
                        Text(listOf(record.language, record.model).filter { it.isNotBlank() }.joinToString(" · "), fontSize = 12.sp, color = colors.onSurfaceVariantSummary)
                    } }
                } else if (reading) {
                    val filtered = state.lines.filter {
                        (displayedMode != "只看译文" || it.translation.isNotBlank()) &&
                            (lineSearch.isBlank() || it.original.contains(lineSearch, true) || it.translation.contains(lineSearch, true))
                    }
                    items(filtered, key = { it.index }) { line ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            SelectionContainer(Modifier.weight(1f)) { Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                if (displayedMode != "只看译文" && line.original.isNotBlank()) Text(line.original,
                                    fontSize = if (displayedMode == "只看原文") fontSize.sp else (fontSize - 2).sp, lineHeight = (fontSize * 1.6).sp,
                                    color = if (displayedMode == "只看原文") colors.onSurface else colors.onSurfaceVariantSummary)
                                if (displayedMode != "只看原文" && line.translation.isNotBlank()) Text(line.translation, fontSize = fontSize.sp, lineHeight = (fontSize * 1.65).sp)
                            } }
                            LibraryIconAction("复制第 ${line.index + 1} 句", R.drawable.ic_copy, tint = colors.onSurfaceVariantSummary.copy(alpha = .6f), onClick = {
                                scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("歌词", listOf(line.original, line.translation).filter { it.isNotBlank() }.joinToString("\n")))); toast("已复制本句") }
                            })
                        }
                    }
                    if (filtered.isEmpty() && !state.loading && state.error == null) item { LibraryEmpty("没有匹配的歌词", "试试其他原文或译文关键词") }
                } else if (state.group != null) {
                    items(state.versions, key = { it.id }) { version ->
                        Box(Modifier.padding(bottom = 12.dp)) { LibraryCard(onClick = { directReader = false; state.openVersion(version) }) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(when (version.status) { "skipped" -> if (version.resolution.startsWith("已收到")) "已收到歌词" else "跳过翻译"; "error" -> "翻译报错"; else -> version.language.ifBlank { "语言未记录（旧记录）" } },
                                    fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                if (version.status == "translated") LibraryTextAction(if (version.favorite) "已收藏" else "收藏", selected = version.favorite, enabled = !state.busy) { state.favorite(version) }
                            }
                            if (version.status != "translated") {
                                Text(version.reason, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                if (version.resolution.isNotBlank()) Text(version.resolution, fontSize = 12.sp, color = colors.primary)
                            }
                            Text(if (version.source == "google") "Google 翻译（保底）" else version.model.ifBlank { "模型未记录" }, fontSize = 13.sp, color = colors.onSurfaceVariantSummary)
                            Text("${libraryDate(version.createdAt)} · ${when { version.hasWordTiming -> "逐字"; version.hasTiming -> "逐行"; else -> "未检测" }}", fontSize = 12.sp, color = colors.onSurfaceVariantSummary)
                        } }
                    }
                    if (state.versions.isEmpty() && !state.loading && state.error == null) item { LibraryEmpty("暂无翻译版本", "记录可能已删除，返回列表刷新即可") }
                } else {
                    val groups = state.songs.groupBy { if (state.query.sort == "title") "按歌曲名称" else translationLibraryDateGroup(it.updatedAt) }
                    groups.forEach { (label, songs) ->
                        item(key = "date:$label") { Text(label, fontSize = 12.sp, color = colors.onSurfaceVariantSummary,
                            modifier = Modifier.padding(start = 3.dp, top = 14.dp, bottom = 9.dp)) }
                        items(songs, key = { it.key }) { song ->
                            LibrarySongRow(song, first = song.key == songs.first().key, last = song.key == songs.last().key,
                                selecting = state.selecting, selected = song.key in state.selected, diagnostic = state.query.status in setOf("skipped", "error"),
                                onClick = { if (state.selecting) state.toggle(song.key) else if (state.query.status in setOf("skipped", "error")) {
                                    directReader = false; state.openSong(song)
                                } else { directReader = true; state.openSongForReading(song) } },
                                onLongClick = { state.selecting = true; state.toggle(song.key) })
                        }
                    }
                    if (state.songs.isEmpty() && !state.loading && state.error == null) item {
                        val filtered = state.query.search.isNotBlank() || state.query.language.isNotBlank() || state.query.favoritesOnly || state.query.updatedFrom != null || state.query.updatedBefore != null
                        LibraryEmpty(if (filtered) "没有符合条件的歌曲" else "还没有翻译记录",
                            if (filtered) "可以调整时间范围、关键词、语言或收藏条件" else "播放歌曲后会记录翻译结果，以及跳过或失败的具体原因")
                    }
                }
                if (state.error != null) item { LibraryCard {
                    Text(state.error!!, fontSize = 14.sp)
                    LibraryButton("重试", onClick = state::refresh)
                } }
                if (state.loading || state.busy) item { Text(if (state.busy) "正在处理…" else "正在加载…", fontSize = 14.sp, modifier = Modifier.padding(16.dp)) }
                val more = if (state.version != null) false else if (state.group != null) state.moreVersions else state.page?.hasMore == true
                if (more && !state.loading) item { LibraryTextAction("加载更多", onClick = state::more) }
            }
        }
        if (state.group == null && state.selecting) {
            Row(Modifier.fillMaxWidth().background(colors.surfaceContainer).padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LibraryIconAction("删除选中记录", R.drawable.ic_delete, enabled = state.selected.isNotEmpty() && !state.busy, tint = colors.error, onClick = ::deleteSelection)
                Box(Modifier.weight(1f)) { LibraryButton("导出 ${state.selected.size} 首", tone = LibraryButtonTone.EXPORT, modifier = Modifier.fillMaxWidth(),
                    enabled = state.selected.isNotEmpty() && !state.busy && state.query.status !in setOf("skipped", "error"), onClick = ::prepareBatchExport) }
            }
        } else if (reading) {
            Row(Modifier.fillMaxWidth().background(colors.surfaceContainer).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f)) {
                    listOf("双语对照", "只看译文", "只看原文").forEach { mode -> LibraryTextAction(
                        when (mode) { "双语对照" -> "双语"; "只看译文" -> "译文"; else -> "原文" }, selected = mode == displayedMode,
                        enabled = mode != "只看原文" || state.version?.hasOriginal == true, onClick = { readingMode = mode }) }
                }
                LibraryButton("导出", tone = LibraryButtonTone.EXPORT, enabled = !state.busy && !state.loading && state.error == null && state.lines.isNotEmpty(), onClick = { showExport = true })
            }
        }
    }
    if (showMore) LibrarySheet("更多操作", onDismiss = { showMore = false }) {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
        LibraryMenuAction("刷新", R.drawable.ic_refresh, enabled = !state.loading && !state.busy) { showMore = false; state.refresh() }
        val version = state.version
        if (version != null && reading) {
            LibraryMenuAction("复制全文", R.drawable.ic_copy, enabled = !state.busy) { showMore = false; state.copy { text -> scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("双语歌词", text))) } } }
            LibraryMenuAction("分享", R.drawable.explain_share, enabled = !state.busy) { showMore = false; state.copy(noticeText = "已准备分享内容") { text ->
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }, "分享双语歌词")) } }
            LibraryMenuAction("查找歌词", R.drawable.list_24px) { showMore = false; showLineSearch = !showLineSearch }
            LibraryMenuAction("阅读设置", R.drawable.ic_format_size) { showMore = false; showReadingOptions = true }
            LibraryMenuAction("版本信息", R.drawable.ic_info) { showMore = false; showVersionInfo = true }
            LibraryMenuAction("删除当前版本", R.drawable.ic_delete, enabled = !state.busy, destructive = true) {
                showMore = false; deletion = LibraryDeletion(ids = listOf(version.id), count = 1, favorites = if (version.favorite) 1 else 0, description = "当前翻译版本")
            }
        } else if (version != null) {
            LibraryMenuAction("复制原因", R.drawable.ic_copy) { showMore = false; scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("翻译处理详情",
                listOf(version.title, version.reason, version.suggestion, version.resolution).filter { it.isNotBlank() }.joinToString("\n")))); toast("已复制处理详情") } }
            LibraryMenuAction("删除记录", R.drawable.ic_delete, enabled = !state.busy, destructive = true) { showMore = false;
                deletion = LibraryDeletion(ids = listOf(version.id), count = 1, favorites = 0, description = "当前处理记录") }
        } else if (state.group != null) {
            LibraryMenuAction("删除整首", R.drawable.ic_delete, enabled = !state.busy, destructive = true) { showMore = false; val song = state.group!!;
                deletion = LibraryDeletion(groups = listOf(song.key), count = song.versions, favorites = song.favorites, description = "《${song.title}》的所有翻译版本") }
        } else {
            LibraryMenuAction("批量选择", R.drawable.list_24px, enabled = !state.busy) { showMore = false; state.selecting = true }
            LibraryMenuAction("删除所有逐行", R.drawable.ic_delete, enabled = !state.busy, destructive = true) { showMore = false; state.prepareLineCleanup() }
        }
        }
    }
    if (showReadingOptions) LibrarySheet("阅读设置", onDismiss = { showReadingOptions = false }) {
        LibrarySettingRow("字号 · $fontSize") {
            LibraryTextAction("字号 −", enabled = fontSize > 13, onClick = { fontSize-- })
            LibraryTextAction("字号 +", enabled = fontSize < 26, onClick = { fontSize++ })
        }
        LibraryMenuAction("回到开头", R.drawable.list_24px) { showReadingOptions = false; listStates[state.version?.id]?.let { scope.launch { it.animateScrollToItem(0) } } }
        LibraryMenuAction("跳到末尾", R.drawable.list_24px) { showReadingOptions = false; listStates[state.version?.id]?.let { scope.launch { it.animateScrollToItem((it.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)) } } }
    }
    if (showVersions && reading) LibrarySheet("翻译版本", onDismiss = { showVersions = false }) {
        LazyColumn(Modifier.heightIn(max = 420.dp)) {
            items(state.versions, key = { it.id }) { version ->
                LibraryMenuAction("${when { version.hasWordTiming -> "逐字"; version.hasTiming -> "逐行"; else -> "未检测" }} · ${libraryDate(version.createdAt)}${if (version.id == state.version?.id) " · 当前" else ""}",
                    R.drawable.explain_history, enabled = !state.loading) { showVersions = false; state.openVersion(version) }
            }
            if (state.moreVersions) item { LibraryTextAction("加载更多版本", enabled = !state.loading, onClick = state::moreSongVersions) }
        }
    }

    if (showExport && state.version != null) LibrarySheet("导出歌词", onDismiss = { showExport = false }) {
        val version = state.version!!
        val originalAvailable = state.lines.isNotEmpty() && state.lines.all { it.original.isNotBlank() }
        val timingAvailable = state.lines.isNotEmpty() && state.lines.all { line ->
            val begin = line.begin; val end = line.end
            begin != null && end != null && begin >= 0 && end > begin
        }
        val content = if (!originalAvailable) TranslationExportContent.TRANSLATION else TranslationExportContent.valueOf(exportContent)
        val format = TranslationExportFormat.valueOf(exportFormat)
        val savedFolder = remember(version.id, state.musicFolderRevision, state.musicLocation) { state.savedMusicFolder(version) }
        LaunchedEffect(version.id) {
            exportFormat = if (timingAvailable) TranslationExportFormat.ENHANCED.name else TranslationExportFormat.TXT.name
            state.locateMusicFolder(version)
        }
        val detectedFolder = state.detectedMusicFolder()
        val detectedGranted = detectedFolder?.let(state::hasMusicFolderAccess) == true
        LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 480.dp).testTag("translation-export-options")) {
            item { LibrarySettingRow("保存位置") { LibraryChoiceMenu(if (exportDestination == "music") "音乐所在文件夹" else "另存为", exportDestination,
                listOf("music" to "音乐所在文件夹", "choose" to "另存为")) {
                    exportDestination = it; exportPreferences.edit().putString("destination", it).apply()
                } } }
            item { LibrarySettingRow("歌词格式") { LibraryChoiceMenu(format.label, format.name, TranslationExportFormat.entries.map { it.name to it.label },
                disabled = if (timingAvailable) emptySet() else TranslationExportFormat.entries.filter { it != TranslationExportFormat.TXT }.map { it.name }.toSet()) { exportFormat = it } } }
            item { LibrarySettingRow("歌词内容") { LibraryChoiceMenu(content.label, content.name, TranslationExportContent.entries.map { it.name to it.label },
                disabled = if (originalAvailable) emptySet() else setOf(TranslationExportContent.ORIGINAL.name, TranslationExportContent.BILINGUAL.name)) { exportContent = it } } }
            if (!timingAvailable) item { Text("缺少时间轴，可导出 TXT。再次播放歌曲可补齐。", fontSize = 12.sp, color = colors.onSurfaceVariantSummary, modifier = Modifier.padding(vertical = 12.dp)) }
            if (exportDestination == "music") item {
                Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(savedFolder?.let { "保存到：${it.name}" }
                        ?: detectedFolder?.let { "已识别：${it.name}" + if (detectedGranted) "\n可直接保存" else "\n首次保存需授权此文件夹" }
                        ?: if (state.locatingMusic) "正在查找音乐所在文件夹…" else state.musicLocation?.message ?: "自动查找音乐所在文件夹",
                        fontSize = 12.sp, color = colors.onSurfaceVariantSummary)
                    LibraryTextAction(if (savedFolder != null || detectedGranted) "更换文件夹" else if (detectedFolder != null) "授权文件夹" else "选择文件夹",
                        enabled = !state.busy && !state.locatingMusic, selected = true) {
                        pendingVersion = TranslationLibraryContract.json.encodeToString(version); folderSettingsOnly = true; pendingFolderKind = "single"
                        state.chooseMusicFolder(version) { launchFolderPicker(it) }
                    }
                    LibraryTextAction("无法授权文件夹？", onClick = { folderHelp = !folderHelp })
                    if (folderHelp) Text("请进入 Music 或具体子文件夹。内部存储根目录、Download 根目录可能禁止授权；也可选择另存为。", fontSize = 12.sp, color = colors.onSurfaceVariantSummary)
                }
            }
            item { Text(translationExportFileName(version, TranslationExportOptions(format, content), state.musicLocation?.audioFileName),
                fontSize = 12.sp, color = colors.onSurfaceVariantSummary, modifier = Modifier.padding(top = 12.dp)) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LibraryTextAction("取消", onClick = { showExport = false })
            Box(Modifier.weight(1f)) { LibraryButton("确认导出", tone = LibraryButtonTone.EXPORT, modifier = Modifier.fillMaxWidth(),
                enabled = !state.busy && (format == TranslationExportFormat.TXT || timingAvailable), onClick = {
                    pendingVersion = TranslationLibraryContract.json.encodeToString(version)
                    pendingFormat = format.name; pendingContent = content.name
                    val options = TranslationExportOptions(format, content); showExport = false
                    if (exportDestination == "music") {
                        folderSettingsOnly = false
                        state.exportToMusicFolder(version, options, onChooseFolder = { initial -> pendingFolderKind = "single"; launchFolderPicker(initial) })
                    } else state.prepareSaveAs(version, options) { fileName -> export.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE); type = format.mimeType; putExtra(Intent.EXTRA_TITLE, fileName)
                    }) }
                }) }
        }
    }

    if (showTimeFilter) LibraryTimeFilter(state.query, state.page?.languages.orEmpty(), onDismiss = { showTimeFilter = false }) { query ->
        state.updateQuery(query)
        showTimeFilter = false
    }
    if (showBatchExport) LibrarySheet("批量导出歌词", onDismiss = { showBatchExport = false }) {
        Text("已选 ${batchSongs().size} 首 · 优先最新逐字版本，无逐字时选最新逐行版本。", fontSize = 12.sp, lineHeight = 19.sp,
            color = colors.onSurfaceVariantSummary, modifier = Modifier.padding(bottom = 12.dp))
        LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 440.dp).testTag("translation-batch-options")) {
            item { LibrarySettingRow("保存位置") { LibraryChoiceMenu(BatchExportDestination.valueOf(batchDestination).label, batchDestination,
                BatchExportDestination.entries.map { it.name to it.label }) { batchDestination = it } } }
            item { LibrarySettingRow("歌词格式") { LibraryChoiceMenu(if (batchFormat == "AUTO") "自动（逐字优先）" else TranslationExportFormat.valueOf(batchFormat).label, batchFormat,
                listOf("AUTO" to "自动（逐字优先）") + TranslationExportFormat.entries.map { it.name to it.label }) { batchFormat = it } } }
            item { LibrarySettingRow("歌词内容") { LibraryChoiceMenu(TranslationExportContent.valueOf(batchContent).label, batchContent,
                TranslationExportContent.entries.map { it.name to it.label }) { batchContent = it } } }
            if (batchDestination != BatchExportDestination.ZIP.name) item { LibrarySettingRow("同名文件") { LibraryChoiceMenu(BatchDuplicatePolicy.valueOf(batchDuplicates).label, batchDuplicates,
                BatchDuplicatePolicy.entries.map { it.name to it.label }) { batchDuplicates = it } } }
            if (batchFormat == "AUTO") item { Text("逐字记录导出增强逐字 LRC，逐行记录导出逐行 LRC；无时间轴时导出 TXT。", fontSize = 12.sp, lineHeight = 19.sp,
                color = colors.onSurfaceVariantSummary, modifier = Modifier.padding(top = 14.dp)) }
            if (batchDestination == BatchExportDestination.MUSIC.name) item {
                Column(Modifier.padding(top = 14.dp)) {
                    Text("逐首保存到对应音乐文件夹；无法定位或未授权的歌曲会显示失败原因。", fontSize = 12.sp, lineHeight = 19.sp, color = colors.onSurfaceVariantSummary)
                    LibraryTextAction("授权音乐文件夹", selected = true, enabled = !state.busy) {
                        pendingFolderKind = "batch-authorize"
                        launchFolderPicker(DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:Music"))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            LibraryTextAction("取消", onClick = { showBatchExport = false })
            Box(Modifier.weight(1f)) { LibraryButton("开始导出", tone = LibraryButtonTone.EXPORT, modifier = Modifier.fillMaxWidth(), enabled = !state.busy && batchSongs().isNotEmpty(), onClick = {
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
                            addCategory(Intent.CATEGORY_OPENABLE); type = "application/zip"; putExtra(Intent.EXTRA_TITLE, "歌词批量导出-$time.zip")
                        })
                    }
                }
            }) }
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
            LibraryButton("确认删除", tone = LibraryButtonTone.DANGER, enabled = !state.busy, onClick = { deletion = null; state.delete(pending.ids, pending.groups) })
        }
    } }
    state.lineCleanup?.let { summary -> WindowDialog(show = true, title = "删除所有逐行记录？", onDismissRequest = state::dismissLineCleanup) {
        Text("将删除整个翻译库的 ${summary.count} 条逐行翻译记录，其中 ${summary.favorites} 条已收藏。此操作不受当前搜索、时间、语言或勾选范围限制。逐字记录、无时间轴记录以及跳过和报错记录会保留。删除后无法恢复。", fontSize = 15.sp)
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LibraryButton("取消", enabled = !state.busy, onClick = state::dismissLineCleanup)
            LibraryButton("确认删除所有逐行", tone = LibraryButtonTone.DANGER, enabled = !state.busy, onClick = state::confirmLineCleanup)
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
private fun LibraryTimeFilter(query: TranslationLibraryQuery, languages: List<String>, onDismiss: () -> Unit,
    onApply: (TranslationLibraryQuery) -> Unit) {
    val colors = MiuixTheme.colorScheme
    var language by rememberSaveable { mutableStateOf(query.language) }
    var from by rememberSaveable { mutableStateOf(query.updatedFrom) }
    var end by rememberSaveable { mutableStateOf(query.updatedBefore?.let { libraryDayStart(it, -1) }) }
    var picking by rememberSaveable { mutableStateOf<String?>(null) }
    val valid = from == null || end == null || from!! <= end!!
    LibrarySheet("筛选翻译记录", onDismiss = onDismiss) {
        Column(Modifier.weight(1f, fill = false).heightIn(max = 450.dp).verticalScroll(rememberScrollState())) {
            LibrarySettingRow("翻译语言") { LibraryChoiceMenu(language.ifBlank { "所有语言" }, language,
                listOf("" to "所有语言") + languages.map { it to it }) { language = it } }
            Text("翻译时间范围", fontSize = 13.sp, color = colors.onSurfaceVariantSummary, modifier = Modifier.padding(top = 18.dp, bottom = 4.dp))
            LibrarySettingRow("开始日期") { LibraryTextAction(from?.let(::libraryDayDate) ?: "不限", modifier = Modifier.testTag("translation-start-date"), onClick = { picking = "from" }) }
            if (from != null) LibraryTextAction("不限开始日期", onClick = { from = null })
            LibrarySettingRow("结束日期") { LibraryTextAction(end?.let(::libraryDayDate) ?: "不限", modifier = Modifier.testTag("translation-end-date"), onClick = { picking = "end" }) }
            if (end != null) LibraryTextAction("不限结束日期", onClick = { end = null })
            Text("按每首歌最近翻译时间筛选；跳过、报错记录按最近发生时间筛选。日期包含首尾两天。", fontSize = 12.sp,
                lineHeight = 19.sp, color = colors.onSurfaceVariantSummary, modifier = Modifier.padding(vertical = 14.dp))
            if (!valid) Text("结束日期不能早于开始日期", fontSize = 13.sp, color = colors.error)
            if (from != null || end != null) LibraryTextAction("清除时间", onClick = { from = null; end = null })
        }
        Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            LibraryTextAction("取消", onClick = onDismiss)
            LibraryTextAction("清除筛选", onClick = { onApply(query.copy(language = "", updatedFrom = null, updatedBefore = null)) })
            Box(Modifier.weight(1f)) { LibraryButton("应用", enabled = valid, tone = LibraryButtonTone.PRIMARY, modifier = Modifier.fillMaxWidth(),
                onClick = { onApply(query.copy(language = language, updatedFrom = from, updatedBefore = end?.let { libraryDayStart(it, 1) })) }) }
        }
    }
    picking?.let { target ->
        LibraryDatePicker(if (target == "from") from else end, onDismiss = { picking = null }) {
            if (target == "from") from = it else end = it
            picking = null
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryDatePicker(value: Long?, onDismiss: () -> Unit, onChoose: (Long) -> Unit) {
    val local = Calendar.getInstance().apply { timeInMillis = value ?: System.currentTimeMillis() }
    val utc = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
        clear(); set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
    }
    val picker = rememberDatePickerState(initialSelectedDateMillis = utc.timeInMillis)
    DatePickerDialog(onDismissRequest = onDismiss, confirmButton = {
        TextButton(enabled = picker.selectedDateMillis != null, onClick = {
            utc.timeInMillis = checkNotNull(picker.selectedDateMillis)
            local.clear(); local.set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH))
            onChoose(local.timeInMillis)
        }) { Text("确定日期", color = MiuixTheme.colorScheme.primary) }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消日期") } }) {
        DatePicker(state = picker, modifier = Modifier.testTag("translation-date-picker"))
    }
}

private fun libraryDayStart(time: Long, dayOffset: Int = 0): Long = Calendar.getInstance().apply {
    timeInMillis = time
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    add(Calendar.DAY_OF_MONTH, dayOffset)
}.timeInMillis

private fun libraryDayDate(time: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(time))

private fun libraryTimeRange(query: TranslationLibraryQuery): String =
    "${query.updatedFrom?.let(::libraryDayDate) ?: "不限"} — ${query.updatedBefore?.let { libraryDayDate(libraryDayStart(it, -1)) } ?: "不限"}"

@Composable
private fun LibraryChoiceMenu(label: String, selected: String, choices: List<Pair<String, String>>, disabled: Set<String> = emptySet(), emphasized: Boolean = true, onChoose: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val colors = MiuixTheme.colorScheme
    Box {
        Row(Modifier.widthIn(max = 220.dp).heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp))
            .clickable(role = Role.Button) { expanded = true }.padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, color = if (emphasized) colors.primary else colors.onSurfaceVariantSummary, fontSize = 13.sp, modifier = Modifier.weight(1f, fill = false))
            Icon(painterResource(R.drawable.explain_chevron_down), null, Modifier.size(15.dp), tint = colors.onSurfaceVariantSummary)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false },
            containerColor = colors.surfaceContainer, shape = RoundedCornerShape(16.dp)) {
            choices.forEach { (key, text) -> DropdownMenuItem(enabled = key !in disabled,
                text = { Text(text, color = (if (key == selected) colors.primary else colors.onSurface).copy(alpha = if (key in disabled) .38f else 1f), fontSize = 14.sp) },
                trailingIcon = { if (key == selected) Text("✓", color = colors.primary) },
                onClick = { expanded = false; onChoose(key) }) }
        }
    }
}

@Composable
private fun LibrarySearch(value: String, hint: String, onChange: (String) -> Unit) {
    val colors = MiuixTheme.colorScheme
    Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
        .background(colors.onSurface.copy(alpha = .06f)).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(R.drawable.library_search), null, Modifier.size(19.dp).padding(end = 2.dp), tint = colors.onSurfaceVariantSummary)
        Spacer(Modifier.width(9.dp))
        BasicTextField(value, onChange, singleLine = true, modifier = Modifier.weight(1f).padding(vertical = 6.dp),
            textStyle = androidx.compose.ui.text.TextStyle(color = colors.onSurface, fontSize = 15.sp), cursorBrush = SolidColor(colors.primary),
            decorationBox = { inner -> Box { if (value.isEmpty()) Text(hint, fontSize = 14.sp, color = colors.onSurfaceVariantSummary); inner() } })
        if (value.isNotEmpty()) LibraryButton("清除", onClick = { onChange("") })
    }
}

@Composable
private fun LibraryButton(text: String, enabled: Boolean = true, selected: Boolean = false,
    tone: LibraryButtonTone = LibraryButtonTone.NORMAL, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    val background = when (tone) {
        LibraryButtonTone.EXPORT, LibraryButtonTone.PRIMARY -> colors.primary
        LibraryButtonTone.DANGER -> colors.error.copy(alpha = .10f)
        LibraryButtonTone.NORMAL -> if (selected) colors.primary.copy(alpha = .12f) else colors.onSurface.copy(alpha = .04f)
    }
    val foreground = when (tone) {
        LibraryButtonTone.EXPORT, LibraryButtonTone.PRIMARY -> colors.onPrimary
        LibraryButtonTone.DANGER -> colors.error
        LibraryButtonTone.NORMAL -> if (selected) colors.primary else colors.onSurface
    }
    Box(modifier.heightIn(min = 46.dp).clip(RoundedCornerShape(14.dp))
        .background(background.copy(alpha = if (enabled) background.alpha else background.alpha * .38f))
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (tone == LibraryButtonTone.EXPORT) Icon(painterResource(R.drawable.library_download), null, Modifier.size(17.dp), tint = foreground.copy(alpha = if (enabled) 1f else .38f))
            Text(text, fontSize = 13.sp, fontWeight = if (tone == LibraryButtonTone.EXPORT) FontWeight.SemiBold else FontWeight.Normal,
                color = foreground.copy(alpha = if (enabled) 1f else .38f))
        }
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
