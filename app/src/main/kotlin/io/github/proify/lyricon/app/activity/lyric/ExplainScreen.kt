package io.github.proify.lyricon.app.activity.lyric

import android.content.ClipData
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.model.DefaultMarkdownColors
import com.mikepenz.markdown.model.DefaultMarkdownTypography
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.model.rememberStreamingMarkdownState
import io.github.proify.lyricon.app.R
import io.github.proify.lyricon.app.ai.explain.*
import io.github.proify.lyricon.app.util.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowCascadingListPopup
import top.yukonga.miuix.kmp.window.WindowDialog
import java.util.Date

@Composable
internal fun ExplainScreen(
    state: ExplainViewModel, openHistory: Boolean = false, openQuickPrompts: Boolean = false,
    album: AlbumExplainViewModel? = null, openAlbum: Boolean = false, onClose: () -> Unit
) {
    var fullscreen by rememberSaveable { mutableStateOf(openHistory || openQuickPrompts || openAlbum) }
    var page by rememberSaveable { mutableStateOf(when {
        openAlbum -> "album"; openQuickPrompts -> "quick_prompts"; openHistory -> "history"; else -> "main"
    }) }
    var historySongKey by rememberSaveable { mutableStateOf("") }
    var historyFavoritesOnly by rememberSaveable { mutableStateOf(false) }
    var readingHistory by rememberSaveable { mutableStateOf(false) }
    var pendingDeletion by remember { mutableStateOf<List<ExplainRecord>>(emptyList()) }
    var deleteTitle by remember { mutableStateOf("") }
    val pageStates = rememberSaveableStateHolder()
    var menu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val keyboard = LocalSoftwareKeyboardController.current
    val colors = MiuixTheme.colorScheme
    fun deleteSong(group: ExplainSongHistory) {
        pendingDeletion = groupExplainHistory(state.records).firstOrNull { it.key == group.key }?.records.orEmpty()
        deleteTitle = "删除《${group.song.title.ifBlank { "未知歌曲" }}》的全部记录？"
    }

    LaunchedEffect(state.notice) {
        state.notice?.let { toast(it); state.clearNotice() }
    }
    LaunchedEffect(state.record.id) { if (page == "lines") page = "main" }
    fun back() {
        if (page == "quick_prompts") keyboard?.hide()
        when {
            page == "song_history" -> page = "history"
            page == "main" && readingHistory -> { readingHistory = false; page = "song_history" }
            page == "history" && openHistory && state.record.song.lines.isEmpty() -> onClose()
            page == "quick_prompts" && openQuickPrompts && state.record.song.lines.isEmpty() -> onClose()
            page == "album" && openAlbum && state.record.song.lines.isEmpty() -> onClose()
            page != "main" -> page = "main"
            fullscreen -> fullscreen = false
            else -> onClose()
        }
    }
    BackHandler { back() }
    // One composition for both sizes keeps scroll position, streamed Markdown and input intact.
    Box(Modifier.fillMaxSize().imePadding()) {
        Box(Modifier.fillMaxSize().testTag("explain-scrim").background(Color.Black.copy(alpha = .35f)).clickable(onClick = onClose))
        Column(
            Modifier.align(Alignment.BottomCenter).widthIn(max = 640.dp).fillMaxWidth()
                .fillMaxHeight(if (fullscreen) 1f else .88f)
                .clip(RoundedCornerShape(topStart = if (fullscreen) 0.dp else 28.dp,
                    topEnd = if (fullscreen) 0.dp else 28.dp))
                .background(colors.surface).testTag("explain-sheet")
                .pointerInput(Unit) { detectTapGestures(onTap = {}) }.navigationBarsPadding()
                .then(if (fullscreen) Modifier.statusBarsPadding() else Modifier)
        ) {
            if (!fullscreen) Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp)
                .size(34.dp, 4.dp).clip(RoundedCornerShape(3.dp)).background(colors.onSurface.copy(alpha = .18f)))
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (page != "main" || readingHistory) IconAction(
                    if (page == "song_history") "返回歌曲列表" else if (page == "main" && readingHistory) "返回歌曲记录" else "返回解读",
                    R.drawable.keyboard_arrow_right_24px, onClick = ::back, modifier = Modifier.rotate(180f))
                Text(when (page) { "history" -> "历史与收藏"; "settings" -> "阅读设置"; "share" -> "分享卡片";
                    "quick_prompts" -> "快捷追问设置";
                    "album" -> "专辑解读";
                    "song_history" -> "歌曲记录"; "lines" -> "选择歌词"; else -> "音乐解读" },
                    modifier = Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                IconAction(if (fullscreen) "收起为弹层" else "全屏阅读", R.drawable.explain_expand,
                    onClick = { fullscreen = !fullscreen })
                IconAction("历史与收藏", R.drawable.explain_history, onClick = { page = "history" })
                Box {
                    IconAction("更多操作", R.drawable.more_horiz_24px, onClick = { menu = true })
                    WindowCascadingListPopup(
                        show = menu,
                        entries = listOf(DropdownEntry(items = listOf(
                            DropdownItem(text = "阅读设置", onClick = { menu = false; page = "settings" }),
                            DropdownItem(text = "快捷追问设置", onClick = { menu = false; page = "quick_prompts" }),
                            DropdownItem(text = "专辑解读", enabled = album != null, onClick = { menu = false; album?.seed(state.record.song); page = "album"; fullscreen = true }),
                            DropdownItem(text = "复制全文", enabled = state.record.turns.any { it.content.isNotBlank() }, onClick = {
                                menu = false
                                scope.launch {
                                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("音乐解读",
                                        state.record.turns.joinToString("\n\n") {
                                            if (it.question.isBlank()) it.content else "${it.question}\n\n${it.content}"
                                        })))
                                    toast("已复制到剪贴板")
                                }
                            }),
                            DropdownItem(text = "关闭解读", onClick = { menu = false; onClose() })
                        ))), onDismissRequest = { menu = false }, alignment = PopupPositionProvider.Align.End
                    )
                }
            }
            pageStates.SaveableStateProvider(if (page == "song_history") "$page:$historySongKey:$historyFavoritesOnly" else page) { when (page) {
                "history" -> HistoryPage(state, Modifier.weight(1f), onOpen = { key, favorites ->
                    historySongKey = key; historyFavoritesOnly = favorites; page = "song_history"
                }, onDelete = ::deleteSong)
                "song_history" -> SongHistoryPage(state, historySongKey, historyFavoritesOnly, Modifier.weight(1f),
                    onOpen = { state.openHistory(it); readingHistory = true; page = "main" },
                    onDelete = { pendingDeletion = listOf(it); deleteTitle = "删除这条解读？" },
                    onDeleteSong = ::deleteSong)
                "settings" -> ReadingPage(state, Modifier.weight(1f))
                "quick_prompts" -> QuickPromptsPage(state, Modifier.weight(1f))
                "album" -> if (album != null) AlbumExplainPage(album, state.fontSize, state.lineSpacing, state.showReasoning, Modifier.weight(1f))
                "share" -> SharePage(state.record, Modifier.weight(1f))
                "lines" -> LyricPicker(state, Modifier.weight(1f)) { state.selectLine(it); page = "main" }
                else -> ExplainMain(state, listState, Modifier.weight(1f),
                    onPick = { page = "lines" }, onShare = { page = "share" },
                    onAlbum = { album?.seed(state.record.song); page = "album"; fullscreen = true }, albumAvailable = album != null,
                    onEditQuickPrompts = { page = "quick_prompts" })
            } }
        }
    }
    if (pendingDeletion.isNotEmpty()) WindowDialog(
        show = true, title = deleteTitle,
        summary = "将删除 ${pendingDeletion.size} 条解读及其中的追问" +
            pendingDeletion.count { it.favorite }.let { if (it > 0) "，包含 $it 条收藏" else "" } + "。此操作无法撤销。",
        onDismissRequest = { pendingDeletion = emptyList() }
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TextButton(text = "取消", onClick = { pendingDeletion = emptyList() }, modifier = Modifier.weight(1f))
            TextButton(text = "删除", colors = ButtonDefaults.textButtonColorsPrimary(),
                enabled = !state.deleting, modifier = Modifier.weight(1f), onClick = {
                    state.deleteRecords(pendingDeletion); pendingDeletion = emptyList()
                })
        }
    }
}

@Composable
private fun ExplainMain(
    state: ExplainViewModel,
    list: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier,
    onPick: () -> Unit,
    onShare: () -> Unit,
    onAlbum: () -> Unit,
    albumAvailable: Boolean,
    onEditQuickPrompts: () -> Unit
) {
    val colors = MiuixTheme.colorScheme
    val scope = rememberCoroutineScope()
    val dragged by list.interactionSource.collectIsDraggedAsState()
    var follow by rememberSaveable { mutableStateOf(true) }
    var question by rememberSaveable { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val lastTurn = state.record.turns.lastOrNull()
    val canAsk = !state.isLoading && state.record.turns.any { it.content.isNotBlank() }
    val awayFromEnd by remember { derivedStateOf { list.canScrollForward } }

    LaunchedEffect(dragged) { if (dragged) follow = false }
    LaunchedEffect(state.record.id, lastTurn?.id) { follow = true }
    LaunchedEffect(lastTurn?.content, lastTurn?.reasoning, state.isLoading) {
        if (follow && list.layoutInfo.totalItemsCount > 0 && lastTurn != null)
            list.scrollToItem(list.layoutInfo.totalItemsCount - 1)
    }
    fun send(text: String) {
        if (canAsk && text.isNotBlank()) {
            state.ask(text)
            question = ""
            follow = true
            keyboard?.hide()
        }
    }
    Column(modifier) {
        Box(Modifier.weight(1f)) {
            LazyColumn(state = list, modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item("song") { SongHeader(state.record.song) }
                if (albumAvailable) item("album-entry") { Action("专辑解读 · 逐首看歌词与编曲 ›", onAlbum, filled = true) }
                item("scope") {
                    Segments(ExplainRange.entries.map { it.label }, state.record.range.ordinal,
                        enabled = state.record.song.lines.isNotEmpty()) { state.setRange(ExplainRange.entries[it]) }
                }
                item("angle") {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        SmallText("解读角度")
                        ChoiceMenu(state.record.angle.label, anglesFor(state.record.range).map { it.label }) {
                            state.setAngle(anglesFor(state.record.range)[it])
                        }
                    }
                }
                item("quote") {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .background(colors.primary.copy(alpha = .08f)).padding(14.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(if (state.record.range == ExplainRange.LINE) {
                                val time = state.selectedLine?.start ?: -1
                                "选中歌词" + if (time >= 0) " · ${lyricTime(time)}" else ""
                            } else "整首歌词 · ${state.record.song.lines.size} 行",
                                fontSize = 12.sp, color = colors.primary)
                            Action(if (state.record.range == ExplainRange.LINE) "换一句 ›" else "查看歌词 ›", onPick, small = true)
                        }
                        Text(if (state.record.range == ExplainRange.LINE) state.selectedLine?.text.orEmpty()
                            else state.record.song.lines.take(2).joinToString("\n") { it.text },
                            fontSize = 16.sp, lineHeight = 26.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                items(state.record.turns, key = { "turn-${it.id}" }) { turn ->
                    TurnView(turn, state.fontSize, state.lineSpacing, state.showReasoning)
                }
                item("state") {
                    state.error?.let {
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(colors.error.copy(alpha = .08f)).padding(12.dp)) {
                            Text(it, color = colors.error, fontSize = 13.sp)
                            Action("重试", state::retryLast)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        SmallText(if (state.sourceLabel in listOf("历史结果", "历史解读"))
                            "${state.sourceLabel} · ${recordTime(state.generatedAt())}" else state.sourceLabel,
                            Modifier.weight(1f))
                        if (state.isLoading) Action("停止生成", state::stop, small = true)
                        else Row {
                            Action(if (state.record.favorite) "已收藏" else "收藏", state::toggleFavorite,
                                enabled = canAsk, small = true, active = state.record.favorite)
                            IconAction("分享解读", R.drawable.explain_share, onShare, enabled = canAsk)
                            IconAction("重新生成", R.drawable.ic_refresh, state::regenerate,
                                enabled = state.record.song.lines.isNotEmpty())
                        }
                    }
                }
                item("suggestions") {
                    if (canAsk) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            SmallText("继续聊聊这首歌", Modifier.weight(1f))
                            Action("自定义", onEditQuickPrompts, small = true)
                        }
                        val prompts = state.quickPrompts[quickPromptGroup(state.record.range, state.record.angle)].orEmpty()
                        FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            prompts.forEach { text -> Action(text, { send(text) }, small = true, filled = true) }
                        }
                    }
                }
            }
            if (!follow && awayFromEnd) Action("↓ 回到最新", {
                follow = true
                scope.launch { list.animateScrollToItem((list.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)) }
            }, modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp), filled = true, small = true)
        }
        Column(Modifier.fillMaxWidth().background(colors.surfaceContainer).padding(12.dp)) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.surface)
                .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(value = question, onValueChange = { question = it.take(4000) },
                    modifier = Modifier.weight(1f).padding(vertical = 6.dp), maxLines = 4,
                    textStyle = TextStyle(color = colors.onSurface, fontSize = 15.sp),
                    cursorBrush = SolidColor(colors.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send(question) }),
                    decorationBox = { inner ->
                        Box { if (question.isEmpty()) SmallText("关于这首歌，还想了解什么？"); inner() }
                    })
                if (state.isLoading) Action("停止", state::stop, active = true)
                else IconAction("发送追问", R.drawable.explain_send, { send(question) },
                    enabled = canAsk && question.isNotBlank(), accent = true)
            }
            Text("AI 解读仅供参考", color = colors.onSurfaceVariantSummary, fontSize = 11.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 7.dp))
        }
    }
}

@Composable
private fun QuickPromptsPage(state: ExplainViewModel, modifier: Modifier) {
    var category by rememberSaveable {
        mutableStateOf(quickPromptGroup(state.record.range, state.record.angle).name)
    }
    val group = QuickPromptGroup.valueOf(category)
    val drafts = rememberSaveableStateHolder()
    Column(modifier.padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            SmallText("分别设置不同场景的快捷问题")
            ChoiceMenu(group.label, QuickPromptGroup.entries.map { it.label }) {
                category = QuickPromptGroup.entries[it].name
            }
        }
        drafts.SaveableStateProvider(group.name) {
            QuickPromptEditor(state, group, Modifier.weight(1f))
        }
    }
}

@Composable
private fun QuickPromptEditor(state: ExplainViewModel, group: QuickPromptGroup, modifier: Modifier) {
    val saved = state.quickPrompts[group].orEmpty()
    var values by rememberSaveable(group.name) { mutableStateOf(saved) }
    var appliedSaved by rememberSaveable(group.name) { mutableStateOf(saved) }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(saved) {
        if (saved != appliedSaved) { values = saved; appliedSaved = saved }
    }
    Column(modifier) {
        SmallText("最多 ${ExplainQuickPrompts.MAX_COUNT} 条，每条最多 ${ExplainQuickPrompts.MAX_LENGTH} 字。空白项和重复项会在保存时移除。",
            Modifier.padding(vertical = 8.dp))
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (values.isEmpty()) item { SmallText("这一组暂不显示快捷问题，仍可在输入框中自由追问。") }
            itemsIndexed(values) { index, text ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    InputBox(text, { newText ->
                        values = values.mapIndexed { i, old ->
                            if (i == index) newText.replace('\n', ' ').replace('\r', ' ')
                                .take(ExplainQuickPrompts.MAX_LENGTH) else old
                        }
                    }, "输入快捷问题", modifier = Modifier.weight(1f), singleLine = true)
                    IconAction("删除第 ${index + 1} 条快捷问题", R.drawable.explain_delete,
                        { values = values.filterIndexed { i, _ -> i != index } }, enabled = !state.savingQuickPrompts)
                }
            }
            item {
                Action("新增快捷问题", { values = values + "" },
                    enabled = values.size < ExplainQuickPrompts.MAX_COUNT && !state.savingQuickPrompts,
                    filled = true, modifier = Modifier.fillMaxWidth())
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Action("恢复默认", {
                keyboard?.hide()
                values = defaultQuickPrompts(group)
                state.saveQuickPrompts(group, values, reset = true)
            }, enabled = !state.savingQuickPrompts, filled = true, modifier = Modifier.weight(1f))
            Action(if (state.savingQuickPrompts) "保存中…" else "保存快捷问题", {
                keyboard?.hide()
                values = cleanQuickPrompts(values)
                state.saveQuickPrompts(group, values)
            }, enabled = !state.savingQuickPrompts, active = true, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun SongHeader(song: ExplainSong) {
    val cover by produceState<Bitmap?>(null, song.coverBase64) {
        value = withContext(Dispatchers.IO) { decodeCover(song.coverBase64) }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(58.dp).clip(RoundedCornerShape(14.dp))
            .background(MiuixTheme.colorScheme.primary.copy(alpha = .10f)), contentAlignment = Alignment.Center) {
            if (cover != null) Image(cover!!.asImageBitmap(), "专辑封面", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(painterResource(R.drawable.ic_music_note), null, tint = MiuixTheme.colorScheme.primary)
        }
        Column(Modifier.weight(1f)) {
            Text(song.title.ifBlank { "未知歌曲" }, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            SmallText(listOf(song.artist.ifBlank { "未知歌手" }, song.album).filter { it.isNotBlank() }.joinToString(" · "))
        }
    }
}

@Composable
internal fun TurnView(turn: ExplainTurn, fontSize: Int, spacing: Int, showReasoning: Boolean) {
    var expanded by rememberSaveable(turn.id) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (turn.question.isNotBlank()) Box(Modifier.fillMaxWidth().padding(start = 32.dp), contentAlignment = Alignment.CenterEnd) {
            Text(turn.question, modifier = Modifier.clip(RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp))
                .background(MiuixTheme.colorScheme.surfaceContainer).padding(12.dp), fontSize = 14.sp)
        }
        if (showReasoning && turn.reasoning.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(MiuixTheme.colorScheme.surfaceContainer).padding(12.dp)) {
                Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
                    SmallText(if (turn.status == ExplainStatus.GENERATING && turn.content.isEmpty()) "正在思考…" else "解读思路",
                        Modifier.weight(1f))
                    Icon(painterResource(R.drawable.keyboard_arrow_right_24px), if (expanded) "收起思路" else "展开思路",
                        Modifier.size(18.dp).rotate(if (expanded) 90f else 0f), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
                if (expanded) ExplainMarkdown(turn.reasoning, fontSize - 1, spacing)
            }
        }
        if (turn.content.isNotEmpty()) SelectionContainer { ExplainMarkdown(turn.content, fontSize, spacing) }
        else if (turn.status == ExplainStatus.GENERATING) SmallText("正在解读，请稍候…")
        if (turn.status == ExplainStatus.STOPPED) SmallText("已停止生成")
    }
}

@Composable
private fun ExplainMarkdown(content: String, size: Int, spacing: Int) {
    val markdown = rememberStreamingMarkdownState()
    var rendered by remember { mutableIntStateOf(0) }
    LaunchedEffect(content) {
        if (content.length > rendered) {
            markdown.append(content.substring(rendered))
            rendered = content.length
        }
    }
    val colors = MiuixTheme.colorScheme
    val typography = remember(size, spacing, colors.primary) {
        val text = TextStyle(fontSize = size.sp, lineHeight = (size * spacing / 10f).sp)
        DefaultMarkdownTypography(
            h1 = text.copy(fontSize = (size + 7).sp, fontWeight = FontWeight.Bold),
            h2 = text.copy(fontSize = (size + 4).sp, fontWeight = FontWeight.Bold),
            h3 = text.copy(fontSize = (size + 2).sp, fontWeight = FontWeight.SemiBold),
            h4 = text.copy(fontWeight = FontWeight.SemiBold), h5 = text, h6 = text,
            text = text, paragraph = text, quote = text, ordered = text, bullet = text, list = text,
            code = text.copy(fontSize = (size - 2).sp), inlineCode = text.copy(fontSize = (size - 2).sp),
            table = text, alertTitle = text.copy(fontWeight = FontWeight.Bold),
            textLink = TextLinkStyles(SpanStyle(color = colors.primary)))
    }
    Markdown(streamingMarkdownState = markdown, modifier = Modifier.fillMaxWidth(),
        colors = DefaultMarkdownColors(text = colors.onSurface, codeBackground = colors.surfaceContainer,
            inlineCodeBackground = colors.surfaceContainer, dividerColor = colors.onSurfaceVariantSummary,
            tableBackground = colors.surfaceContainer), typography = typography, padding = markdownPadding(block = 8.dp))
}

@Composable
private fun HistoryPage(
    state: ExplainViewModel, modifier: Modifier, onOpen: (String, Boolean) -> Unit,
    onDelete: (ExplainSongHistory) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var favorites by rememberSaveable { mutableStateOf(false) }
    val filtered = remember(state.records, query, favorites) {
        groupExplainHistory(state.records, favorites).filter {
            query.isBlank() || it.song.title.contains(query.trim(), true) || it.song.artist.contains(query.trim(), true)
        }
    }
    Column(modifier.padding(horizontal = 20.dp)) {
        InputBox(query, { query = it }, "搜索歌曲、歌手", Modifier.padding(vertical = 10.dp), singleLine = true)
        Segments(listOf("全部记录", "我的收藏"), if (favorites) 1 else 0) { favorites = it == 1 }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (filtered.isEmpty()) item { SmallText(if (favorites) "暂无收藏的解读" else "暂无符合条件的解读记录") }
            items(filtered, key = { it.key }) { song ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainer).clickable { onOpen(song.key, favorites) }.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { SongHeader(song.song) }
                        IconAction("删除这首歌的全部记录", R.drawable.explain_delete, { onDelete(song) }, enabled = !state.deleting)
                        Icon(painterResource(R.drawable.keyboard_arrow_right_24px), null,
                            Modifier.size(20.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                    val count = if (favorites) "${song.records.size} 条收藏" else "${song.records.size} 条解读"
                    val saved = song.records.count { it.favorite }
                    SmallText(count + if (!favorites && saved > 0) " · $saved 条收藏" else "",
                        Modifier.padding(top = 12.dp))
                    SmallText("最近更新 · ${recordTime(song.records.first().updatedAt)}", Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun SongHistoryPage(
    state: ExplainViewModel, songKey: String, favoritesOnly: Boolean, modifier: Modifier,
    onOpen: (ExplainRecord) -> Unit, onDelete: (ExplainRecord) -> Unit,
    onDeleteSong: (ExplainSongHistory) -> Unit
) {
    val group = remember(state.records, songKey, favoritesOnly) {
        groupExplainHistory(state.records, favoritesOnly).firstOrNull { it.key == songKey }
    }
    LazyColumn(modifier.padding(horizontal = 20.dp), contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (group == null) item { SmallText(if (favoritesOnly) "这首歌暂无收藏的解读" else "这首歌暂无解读记录") }
        else {
            item("song") { SongHeader(group.song) }
            item("delete") { Action("删除这首歌的全部记录", { onDeleteSong(group) }, enabled = !state.deleting, small = true) }
            item("count") { SmallText("${if (favoritesOnly) "收藏版本" else "全部版本"} · ${group.records.size} 条 · 按最近更新时间排列") }
            items(group.records, key = { it.id }) { record ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainer).clickable { onOpen(record) }.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${record.range.label} · ${record.angle.label}", modifier = Modifier.weight(1f),
                            fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        if (record.favorite) Text("已收藏", fontSize = 12.sp, color = MiuixTheme.colorScheme.primary)
                        IconAction("删除此条解读", R.drawable.explain_delete, { onDelete(record) }, enabled = !state.deleting)
                        Icon(painterResource(R.drawable.keyboard_arrow_right_24px), null,
                            Modifier.size(18.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                    if (record.range == ExplainRange.LINE) record.song.lines.getOrNull(record.lineIndex)?.text?.let {
                        Text(it, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 8.dp))
                    }
                    record.turns.firstOrNull { it.content.isNotBlank() }?.let {
                        Text(ExplainShareCard.plainText(it.content), fontSize = 13.sp, maxLines = 2,
                            overflow = TextOverflow.Ellipsis, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(top = 7.dp))
                    }
                    val questions = record.turns.count { it.question.isNotBlank() }
                    SmallText(recordTime(record.updatedAt) + if (questions > 0) " · $questions 次追问" else "",
                        Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun LyricPicker(state: ExplainViewModel, modifier: Modifier, onPick: (Int) -> Unit) {
    val list = rememberLazyListState(initialFirstVisibleItemIndex = state.record.lineIndex.coerceAtLeast(0))
    Column(modifier.padding(horizontal = 20.dp)) {
        if (state.canRefresh) Action(if (state.refreshing) "正在获取…" else "更新为正在播放的这一句", state::refreshCurrentLine,
            enabled = !state.refreshing, filled = true, modifier = Modifier.fillMaxWidth())
        SmallText("播放继续时不会自动更换解读对象", Modifier.padding(vertical = 10.dp))
        LazyColumn(state = list, modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(state.record.song.lines) { index, line ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(if (index == state.record.lineIndex) MiuixTheme.colorScheme.primary.copy(alpha = .10f)
                        else MiuixTheme.colorScheme.surfaceContainer).clickable { onPick(index) }.padding(13.dp)) {
                    if (line.start >= 0) SmallText(lyricTime(line.start))
                    Text(line.text, fontSize = 15.sp)
                }
            }
        }
    }
}

@Composable
private fun ReadingPage(state: ExplainViewModel, modifier: Modifier) {
    LazyColumn(modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(vertical = 12.dp)) {
        item {
            SettingCard {
                Text("正文字号 · ${state.fontSize} sp")
                ReadingSlider(state.fontSize.toFloat(), 14f..22f, 7) { state.setReading(size = it.toInt()) }
            }
        }
        item {
            SettingCard {
                Text("行距 · ${state.lineSpacing / 10f} 倍")
                ReadingSlider(state.lineSpacing.toFloat(), 14f..22f, 7) { state.setReading(spacing = it.toInt()) }
            }
        }
        item {
            SettingCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("显示解读思路入口", modifier = Modifier.weight(1f))
                    Switch(checked = state.showReasoning, onCheckedChange = { state.setReading(reasoning = it) },
                        colors = SwitchDefaults.colors(checkedThumbColor = MiuixTheme.colorScheme.onPrimary,
                            checkedTrackColor = MiuixTheme.colorScheme.primary, checkedBorderColor = Color.Transparent,
                            uncheckedThumbColor = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            uncheckedTrackColor = MiuixTheme.colorScheme.surface,
                            uncheckedBorderColor = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = .4f)))
                }
            }
        }
        item {
            SmallText("阅读预览 · 设置自动保存", Modifier.padding(bottom = 8.dp))
            SettingCard {
                Text(state.selectedLine?.text ?: "每一句歌词，都有自己的情绪与故事。",
                    fontSize = state.fontSize.sp, lineHeight = (state.fontSize * state.lineSpacing / 10f).sp)
            }
        }
    }
}

@Composable
private fun SharePage(record: ExplainRecord, modifier: Modifier) {
    val answers = record.turns.filter { it.content.isNotBlank() }
    var answerIndex by rememberSaveable(record.id) { mutableIntStateOf(answers.lastIndex.coerceAtLeast(0)) }
    var excerpt by rememberSaveable(record.id) { mutableStateOf(ExplainShareCard.plainText(answers.lastOrNull()?.content.orEmpty()).take(600)) }
    var quoteIndex by rememberSaveable(record.id) { mutableIntStateOf(record.lineIndex.coerceAtLeast(0)) }
    val quote = record.song.lines.getOrNull(quoteIndex)?.text.orEmpty()
    val colors = MiuixTheme.colorScheme
    val palette = ExplainCardColors(colors.surfaceContainer.toArgb(), colors.onSurface.toArgb(),
        colors.onSurfaceVariantSummary.toArgb(), colors.primary.toArgb())
    var previewError by remember { mutableStateOf(false) }
    val preview by produceState<Bitmap?>(null, record.id, quote, excerpt, palette) {
        previewError = false
        value = null
        kotlinx.coroutines.delay(180)
        try { value = withContext(Dispatchers.Default) { ExplainShareCard.render(record.song, quote, excerpt, palette) } }
        catch (_: Exception) { previewError = true }
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    LazyColumn(modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
        item { Text("选择歌词", fontWeight = FontWeight.Medium) }
        item {
            ChoiceMenu(if (quote.isBlank()) "选择一句歌词" else quote,
                record.song.lines.map { it.text }, modifier = Modifier.fillMaxWidth()) { quoteIndex = it }
        }
        if (answers.size > 1) item {
            ChoiceMenu(if (answerIndex == 0) "首次解读" else "追问回答 $answerIndex",
                answers.mapIndexed { i, _ -> if (i == 0) "首次解读" else "追问回答 $i" }) {
                answerIndex = it
                excerpt = ExplainShareCard.plainText(answers[it].content).take(600)
            }
        }
        item { Text("解读摘录", fontWeight = FontWeight.Medium) }
        item { InputBox(excerpt, { excerpt = it.take(1800) }, "输入或粘贴想分享的解读片段", minLines = 3) }
        item { SmallText("${excerpt.length}/1800 · 可编辑摘录") }
        item {
            if (preview != null) Image(preview!!.asImageBitmap(), "歌词解读分享卡预览",
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .aspectRatio(preview!!.width.toFloat() / preview!!.height), contentScale = ContentScale.FillWidth)
            else SmallText(if (previewError) "预览生成失败，请调整摘录后重试" else "正在生成分享卡…")
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Action(if (exporting) "处理中…" else "保存图片", {
                    val image = preview ?: return@Action
                    exporting = true
                    scope.launch {
                        try { ExplainShareCard.save(context, image); toast("已保存到相册 Pictures/Lyricon") }
                        catch (_: Exception) { toast("保存失败，请检查存储空间后重试") }
                        finally { exporting = false }
                    }
                }, enabled = preview != null && excerpt.isNotBlank() && !exporting, active = true,
                    modifier = Modifier.weight(1f))
                Action("系统分享", {
                    val image = preview ?: return@Action
                    exporting = true
                    scope.launch {
                        try { ExplainShareCard.share(context, image) }
                        catch (_: Exception) { toast("暂时无法分享，请重试") }
                        finally { exporting = false }
                    }
                }, enabled = preview != null && excerpt.isNotBlank() && !exporting, filled = true,
                    modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Segments(labels: List<String>, selected: Int, enabled: Boolean = true, onSelect: (Int) -> Unit) {
    val colors = MiuixTheme.colorScheme
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.surfaceContainer).padding(4.dp)) {
        labels.forEachIndexed { index, label ->
            Box(Modifier.weight(1f).clip(RoundedCornerShape(11.dp))
                .background(if (index == selected) colors.surface else Color.Transparent)
                .clickable(enabled = enabled, role = Role.Tab) { onSelect(index) }
                .padding(vertical = 11.dp), contentAlignment = Alignment.Center) {
                Text(label, fontSize = 14.sp, fontWeight = if (index == selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (index == selected) colors.onSurface else colors.onSurfaceVariantSummary)
            }
        }
    }
}

@Composable
private fun ChoiceMenu(label: String, choices: List<String>, modifier: Modifier = Modifier, onChoose: (Int) -> Unit) {
    var show by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(Modifier.clip(RoundedCornerShape(12.dp)).background(MiuixTheme.colorScheme.surfaceContainer)
            .clickable(role = Role.DropdownList) { show = true }.heightIn(min = 44.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f, fill = false), maxLines = 2,
                overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(8.dp))
            Icon(
                painter = painterResource(R.drawable.explain_chevron_down),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        // Long lyric lists use the dedicated picker; share selection remains searchable by scrolling.
        WindowCascadingListPopup(show = show, entries = listOf(DropdownEntry(items = choices.mapIndexed { index, text ->
            DropdownItem(text = text, onClick = { show = false; onChoose(index) })
        })), onDismissRequest = { show = false }, alignment = PopupPositionProvider.Align.End)
    }
}

@Composable
internal fun InputBox(
    value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier,
    singleLine: Boolean = false, minLines: Int = 1, enabled: Boolean = true
) {
    val colors = MiuixTheme.colorScheme
    BasicTextField(value, onChange, modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
        .background(colors.surfaceContainer).padding(13.dp), singleLine = singleLine, minLines = minLines,
        textStyle = TextStyle(color = colors.onSurface, fontSize = 15.sp, lineHeight = 24.sp),
        cursorBrush = SolidColor(colors.primary), decorationBox = { inner ->
            Box { if (value.isEmpty()) SmallText(hint); inner() }
        }, enabled = enabled)
}

@Composable
internal fun Action(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    small: Boolean = false, filled: Boolean = false, active: Boolean = false
) {
    val colors = MiuixTheme.colorScheme
    Box(modifier.clip(RoundedCornerShape(12.dp))
        .background(if (active) colors.primary.copy(alpha = .12f) else if (filled) colors.surfaceContainer else Color.Transparent)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).heightIn(min = 44.dp)
        .padding(horizontal = if (small) 10.dp else 14.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(text, fontSize = if (small) 12.sp else 14.sp,
            color = (if (active) colors.primary else colors.onSurface).copy(alpha = if (enabled) 1f else .4f))
    }
}

@Composable
private fun IconAction(
    label: String, resource: Int, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, accent: Boolean = false
) {
    val colors = MiuixTheme.colorScheme
    Box(modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
        .background(if (accent) colors.primary.copy(alpha = .12f) else Color.Transparent)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(painterResource(resource), label, Modifier.size(21.dp),
            tint = (if (accent) colors.primary else colors.onSurface).copy(alpha = if (enabled) 1f else .35f))
    }
}

@Composable
internal fun SmallText(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
}

@Composable
private fun SettingCard(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp))
        .background(MiuixTheme.colorScheme.surfaceContainer).padding(16.dp), content = content)
}

@Composable
private fun ReadingSlider(value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, onChange: (Float) -> Unit) {
    Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps,
        colors = SliderDefaults.colors(thumbColor = MiuixTheme.colorScheme.primary,
            activeTrackColor = MiuixTheme.colorScheme.primary,
            activeTickColor = MiuixTheme.colorScheme.onPrimary.copy(alpha = .6f),
            inactiveTickColor = MiuixTheme.colorScheme.primary.copy(alpha = .3f),
            inactiveTrackColor = MiuixTheme.colorScheme.onSurface.copy(alpha = .12f)))
}

private fun lyricTime(time: Long): String = "%02d:%02d".format(time / 60000, time / 1000 % 60)
private fun recordTime(time: Long): String = DateFormat.format("MM-dd HH:mm", Date(time)).toString()
private fun decodeCover(encoded: String?): Bitmap? = encoded?.let {
    runCatching { Base64.decode(it, Base64.DEFAULT).let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size) } }.getOrNull()
}
