package io.github.proify.lyricon.app.ai.explain

import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.libxposed.service.XposedService
import io.github.proify.lyricon.app.LyriconApp
import io.github.proify.lyricon.app.util.LyricPrefs
import io.github.proify.lyricon.lyric.ai.core.AiConfig
import io.github.proify.lyricon.lyric.ai.core.AiConfigProviderImpl
import io.github.proify.lyricon.lyric.ai.explain.AiExplainContract as Contract
import io.github.proify.lyricon.lyric.ai.explain.ExplainLyricSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

fun explainSongFromBundle(bundle: Bundle): ExplainSong {
    val texts = bundle.getStringArrayList(Contract.EXTRA_LINE_TEXTS)
        ?: ArrayList(bundle.getString(Contract.EXTRA_LYRICS).orEmpty().lineSequence()
            .map { it.removePrefix("- ") }.filter { it.isNotBlank() }.toList())
    val starts = bundle.getLongArray(Contract.EXTRA_LINE_STARTS)
    val ends = bundle.getLongArray(Contract.EXTRA_LINE_ENDS)
    return ExplainSong(
        title = bundle.getString(Contract.EXTRA_TITLE).orEmpty(),
        artist = bundle.getString(Contract.EXTRA_ARTIST).orEmpty(),
        album = bundle.getString(Contract.EXTRA_ALBUM).orEmpty(),
        lines = texts.mapIndexed { i, text -> ExplainLine(text, starts?.getOrNull(i) ?: -1, ends?.getOrNull(i) ?: -1) },
        coverBase64 = bundle.getByteArray(Contract.EXTRA_COVER)?.takeIf { it.size <= 100_000 }
            ?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
    )
}

class ExplainViewModel(
    application: Application,
    initialSong: ExplainSong,
    initialPosition: Long,
    private val requester: Messenger?,
    private val configProvider: suspend () -> AiConfig? = ::resolveExplainConfig
) : AndroidViewModel(application) {
    private val history = ExplainHistory(application)
    private val reading = application.getSharedPreferences("ai_explain_reading", 0)
    private val quickPromptStore = ExplainQuickPrompts(application)
    var quickPrompts by mutableStateOf(QuickPromptGroup.entries.associateWith { quickPromptStore.get(it) })
        private set
    var savingQuickPrompts by mutableStateOf(false)
        private set
    private var requestJob: Job? = null
    private var selectionJob: Job? = null
    private var startupPending = true
    private var refreshJob: Job? = null
    private var sourceTime = System.currentTimeMillis()

    var fontSize by mutableStateOf(reading.getInt("font_size", 15).coerceIn(14, 22))
        private set
    var lineSpacing by mutableStateOf(reading.getInt("line_spacing", 18).coerceIn(14, 22))
        private set
    var showReasoning by mutableStateOf(reading.getBoolean("show_reasoning", true))
        private set
    var records by mutableStateOf<List<ExplainRecord>>(emptyList())
        private set
    var notice by mutableStateOf<String?>(null)
        private set
    var sourceLabel by mutableStateOf("")
        private set
    var refreshing by mutableStateOf(false)
        private set
    var deleting by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private val initialIndex = ExplainLyricSelection.currentIndex(
        initialSong.lines.map { it.start }, initialSong.lines.map { it.end }, initialPosition)
    private var lineSong = initialSong
    private var lineIndex = initialIndex.coerceAtLeast(0)
    var record by mutableStateOf(newRecord(initialSong, ExplainRange.SONG, ExplainAngle.OVERVIEW, initialIndex))
        private set

    val isLoading: Boolean get() = record.turns.lastOrNull()?.status == ExplainStatus.GENERATING
    val canRefresh: Boolean get() = requester != null
    val selectedLine: ExplainLine? get() = record.song.lines.getOrNull(record.lineIndex)

    init {
        val startupId = record.id
        viewModelScope.launch {
            runCatching { history.all() }.onSuccess { records = it }
                .onFailure { notice = "解读历史读取失败，原记录已保留" }
            if (!startupPending || record.id != startupId) return@launch
            if (initialSong.lines.isNotEmpty() || initialSong.title.isNotBlank()) restoreOrGenerate(anyAngle = true)
            else error = "暂无歌词可解读"
        }
    }

    private fun newRecord(song: ExplainSong, range: ExplainRange, angle: ExplainAngle, index: Int) =
        ExplainRecord(id(), song, range, angle, index, emptyList(), System.currentTimeMillis())

    fun setRange(range: ExplainRange) {
        if (record.range == range || record.song.lines.isEmpty()) return
        replaceSelection(range, anglesFor(range).first(), lineIndex, anyAngle = true,
            song = if (range == ExplainRange.LINE) lineSong else record.song)
    }

    fun setAngle(angle: ExplainAngle) {
        if (angle == record.angle || angle !in anglesFor(record.range)) return
        replaceSelection(record.range, angle, record.lineIndex)
    }

    fun selectLine(index: Int) {
        if (index !in record.song.lines.indices) return
        lineSong = record.song
        lineIndex = index
        replaceSelection(ExplainRange.LINE,
            record.angle.takeIf { it in anglesFor(ExplainRange.LINE) } ?: ExplainAngle.PHRASE, index)
    }

    private fun replaceSelection(range: ExplainRange, angle: ExplainAngle, index: Int,
        anyAngle: Boolean = false, song: ExplainSong = record.song) {
        stop()
        record = newRecord(song, range, angle, index)
        sourceLabel = ""
        restoreOrGenerate(anyAngle)
    }

    private fun restoreOrGenerate(anyAngle: Boolean) {
        selectionJob?.cancel()
        val selection = record
        error = null
        selectionJob = viewModelScope.launch {
            val saved = runCatching { history.all() }.getOrElse {
                if (it is CancellationException) throw it
                notice = "解读历史读取失败，原记录已保留"
                emptyList()
            }
            if (record.id != selection.id) return@launch
            records = saved
            val previous = latestExplainReading(saved, selection, anyAngle)
            if (previous == null) {
                if (selection.song.lines.isEmpty()) error = "暂无歌词可解读"
                else generate()
            }
            else displayHistory(previous)
        }
    }

    fun regenerate() {
        if (record.song.lines.isEmpty()) { error = "暂无歌词可解读"; return }
        stop()
        record = newRecord(record.song, record.range, record.angle, record.lineIndex)
        generate(force = true)
    }

    fun ask(question: String) {
        if (isLoading || question.isBlank() || record.turns.none { it.content.isNotBlank() }) return
        record = record.forContinuation(id())
        generate(question = question.trim().take(4000))
    }

    fun retryLast() {
        if (isLoading) return
        val last = record.turns.lastOrNull() ?: return regenerate()
        if (last.question.isBlank()) return regenerate()
        // Keep the interrupted answer visible and exclude it from the retry's context.
        record = record.forContinuation(id())
        generate(question = last.question, excludedTurn = last.id)
    }

    fun stop() {
        startupPending = false
        selectionJob?.cancel()
        requestJob?.cancel()
        if (isLoading) {
            val last = record.turns.last()
            updateTurn(last.id) { it.copy(status = ExplainStatus.STOPPED) }
            sourceLabel = "已停止 · 已有内容保留"
            saveCurrent()
        }
    }

    fun openHistory(item: ExplainRecord) {
        stop()
        if (explainSongIdentity(item.song) != explainSongIdentity(lineSong) || lineSong.lines.isEmpty()) {
            lineSong = item.song
            lineIndex = item.lineIndex.coerceAtLeast(0)
        }
        displayHistory(item)
    }

    private fun displayHistory(item: ExplainRecord) {
        record = item.copy(turns = item.turns.map {
            if (it.status == ExplainStatus.GENERATING) it.copy(status = ExplainStatus.STOPPED) else it
        })
        error = null
        sourceTime = item.createdAt
        sourceLabel = "历史解读"
    }

    fun toggleFavorite() {
        if (isLoading || record.turns.none { it.content.isNotBlank() }) return
        val previous = record
        val next = record.copy(favorite = !record.favorite,
            updatedAt = maxOf(System.currentTimeMillis(), record.updatedAt + 1))
        record = next
        viewModelScope.launch {
            try {
                withContext(NonCancellable) { records = history.put(next) }
                notice = if (next.favorite) "已收藏此版本" else "已取消收藏"
            } catch (_: Exception) {
                if (record == next) record = previous
                notice = "收藏保存失败，请重试"
            }
        }
    }

    fun deleteRecords(items: List<ExplainRecord>) {
        if (deleting || items.isEmpty()) return
        val ids = items.map { it.id }.toSet()
        deleting = true
        viewModelScope.launch {
            try {
                withContext(NonCancellable) { records = history.delete(ids) }
                if (record.id in ids) {
                    stop()
                    // Keep the visible reading copy, but future explicit actions create a new version.
                    record = record.copy(id = id(), favorite = false)
                }
                notice = "已删除 ${ids.size} 条解读记录"
            } catch (_: Exception) { notice = "删除失败，请稍后重试" }
            finally { deleting = false }
        }
    }

    fun setReading(size: Int = fontSize, spacing: Int = lineSpacing, reasoning: Boolean = showReasoning) {
        fontSize = size.coerceIn(14, 22)
        lineSpacing = spacing.coerceIn(14, 22)
        showReasoning = reasoning
        reading.edit().putInt("font_size", fontSize).putInt("line_spacing", lineSpacing)
            .putBoolean("show_reasoning", showReasoning).apply()
    }

    fun saveQuickPrompts(group: QuickPromptGroup, values: List<String>, reset: Boolean = false) {
        if (savingQuickPrompts) return
        val cleaned = try { cleanQuickPrompts(values) }
        catch (failure: IllegalArgumentException) { notice = failure.message; return }
        savingQuickPrompts = true
        viewModelScope.launch {
            try {
                val saved = withContext(NonCancellable + Dispatchers.IO) {
                    if (reset) quickPromptStore.reset(group) else quickPromptStore.save(group, cleaned)
                }
                quickPrompts = quickPrompts + (group to saved)
                notice = if (reset) "已恢复这一组的默认问题" else "快捷问题已保存"
            } catch (_: Exception) { notice = "快捷问题保存失败，请重试" }
            finally { savingQuickPrompts = false }
        }
    }

    fun clearNotice() { notice = null }

    fun refreshCurrentLine() {
        val remote = requester ?: return
        if (refreshing) return
        val expectedSong = record.song
        refreshJob = viewModelScope.launch {
            refreshing = true
            try {
                val snapshot = withTimeoutOrNull(3000) {
                    suspendCancellableCoroutine<Bundle> { continuation ->
                        val reply = Messenger(Handler(Looper.getMainLooper()) { message ->
                            if (message.what == Contract.RESULT_SNAPSHOT && continuation.isActive)
                                continuation.resume(message.data)
                            true
                        })
                        try { remote.send(Message.obtain(null, Contract.REQUEST_SNAPSHOT).apply { replyTo = reply }) }
                        catch (_: Exception) { continuation.resume(Bundle()) }
                    }
                }
                if (snapshot == null || snapshot.isEmpty) {
                    notice = "暂时无法获取播放位置，请稍后重试"
                } else {
                    val latest = explainSongFromBundle(snapshot)
                    if (!expectedSong.sameTrack(latest) || !record.song.sameTrack(expectedSong)) {
                        notice = "播放歌曲已变化，请从状态栏重新打开解读"
                    } else {
                        val index = ExplainLyricSelection.currentIndex(latest.lines.map { it.start },
                            latest.lines.map { it.end }, snapshot.getLong(Contract.EXTRA_POSITION))
                        if (index >= 0) selectLine(index)
                        else notice = "当前处于前奏或间奏，可以手动选一句"
                    }
                }
            } finally { refreshing = false }
        }
    }

    private fun generate(force: Boolean = false, question: String = "", excludedTurn: String? = null) {
        requestJob?.cancel()
        error = null
        sourceLabel = "正在生成…"
        val contextTurns = record.turns.filterNot { it.id == excludedTurn }
        val turn = ExplainTurn(id(), question)
        record = record.copy(turns = record.turns + turn, updatedAt = System.currentTimeMillis())
        val request = record
        requestJob = viewModelScope.launch {
            try {
                val config = configProvider()
                if (config == null) {
                    error = "请在「AI 实验室」中配置好 API 后再试"
                    sourceLabel = "尚未配置 AI"
                    updateTurn(turn.id) { it.copy(status = ExplainStatus.ERROR) }
                    return@launch
                }
                val customPrompt = withContext(Dispatchers.IO) {
                    LyricPrefs.basicStylePrefs.getString(AiExplainPrompt.KEY_CUSTOM_SYSTEM_PROMPT, null)
                }
                val language = Locale.getDefault().toLanguageTag()
                val cacheKey = explainCacheKey(request.song, request.range, request.angle, request.lineIndex,
                    config, language, AiExplainPrompt.explainSystemPrompt(customPrompt, request.range, request.angle))
                if (question.isBlank() && !force) {
                    val cached = withContext(Dispatchers.IO) { AiExplainCache.get(getApplication(), cacheKey) }
                    if (cached != null && cached.content.isNotBlank()) {
                        updateTurn(turn.id) { it.copy(content = cached.content, reasoning = cached.reasoning,
                            status = ExplainStatus.COMPLETE) }
                        sourceTime = cached.time
                        record = record.copy(createdAt = cached.time)
                        sourceLabel = "历史结果"
                        return@launch
                    }
                }
                val events = Channel<Pair<Boolean, String>>(Channel.UNLIMITED)
                val result = coroutineScope {
                    fun drain(first: Pair<Boolean, String>? = null) {
                        val content = StringBuilder()
                        val reasoning = StringBuilder()
                        fun add(event: Pair<Boolean, String>) {
                            if (event.first) reasoning.append(event.second) else content.append(event.second)
                        }
                        first?.let(::add)
                        while (true) add(events.tryReceive().getOrNull() ?: break)
                        if (request.id == record.id) updateTurn(turn.id) {
                            it.copy(content = it.content + content, reasoning = it.reasoning + reasoning)
                        }
                    }
                    val consumer = launch {
                        try {
                            for (event in events) {
                                drain(event)
                                delay(45)
                            }
                        } finally { withContext(NonCancellable + Dispatchers.Main.immediate) { drain() } }
                    }
                    try {
                        AiExplainClient.stream(config, language, customPrompt,
                            request.song.title, request.song.artist, request.song.album, request.song.lyrics,
                            onReasoning = { events.trySend(true to it) },
                            onContent = { events.trySend(false to it) },
                            range = request.range, angle = request.angle, lineIndex = request.lineIndex,
                            selectedLine = request.song.lines.getOrNull(request.lineIndex)?.text.orEmpty(),
                            conversation = if (question.isBlank()) emptyList() else conversationFor(contextTurns, question))
                    } finally {
                        events.close()
                        consumer.join()
                    }
                }
                if (result == null) {
                    error = "生成失败，已保留收到的内容。请检查网络或 AI 配置后重试。"
                    updateTurn(turn.id) { it.copy(status = ExplainStatus.ERROR) }
                    sourceLabel = "生成中断"
                } else {
                    updateTurn(turn.id) { it.copy(content = result.content, reasoning = result.reasoning,
                        status = ExplainStatus.COMPLETE) }
                    sourceTime = System.currentTimeMillis()
                    sourceLabel = "刚刚生成"
                    if (question.isBlank()) withContext(Dispatchers.IO) {
                        AiExplainCache.put(getApplication(), cacheKey, AiExplainCached(result.reasoning, result.content, sourceTime))
                    }
                }
            } catch (cancelled: CancellationException) {
                if (request.id == record.id) {
                    updateTurn(turn.id) {
                        if (it.status == ExplainStatus.GENERATING) it.copy(status = ExplainStatus.STOPPED) else it
                    }
                    if (record.turns.lastOrNull()?.let { it.id == turn.id && it.status == ExplainStatus.STOPPED } == true)
                        sourceLabel = "已停止 · 已有内容保留"
                }
                throw cancelled
            } catch (_: Exception) {
                if (request.id == record.id) {
                    error = "生成失败，已保留收到的内容，请重试"
                    updateTurn(turn.id) { it.copy(status = ExplainStatus.ERROR) }
                    sourceLabel = "生成中断"
                }
            } finally {
                if (record.id == request.id) saveCurrent()
            }
        }
    }

    private fun updateTurn(turnId: String, transform: (ExplainTurn) -> ExplainTurn) {
        if (record.turns.none { it.id == turnId }) return
        record = record.copy(turns = record.turns.map { if (it.id == turnId) transform(it) else it })
    }

    private fun saveCurrent() {
        if (record.turns.none { it.content.isNotBlank() }) return
        val snapshot = record.copy(updatedAt = maxOf(System.currentTimeMillis(), record.updatedAt + 1))
        record = snapshot
        viewModelScope.launch {
            try { withContext(NonCancellable) { records = history.put(snapshot) } }
            catch (_: Exception) { notice = "解读历史保存失败，请检查存储空间" }
        }
    }

    fun generatedAt(): Long = sourceTime

    companion object { private fun id() = UUID.randomUUID().toString() }
}

internal suspend fun resolveExplainConfig(): AiConfig? {
        fun read() = AiConfigProviderImpl(LyricPrefs.basicStylePrefs).getActiveConfig()?.takeIf { it.isUsable }
        read()?.let { return it }
        if (LyriconApp.xposedService == null) withTimeoutOrNull(2000) {
            suspendCancellableCoroutine<Unit> { continuation ->
                val resumed = AtomicBoolean(false)
                val listener = object : LyriconApp.XposedServiceStateListener {
                    override fun onServiceStateChanged(service: XposedService?) {
                        if (service != null && continuation.isActive && resumed.compareAndSet(false, true)) {
                            LyriconApp.removeXposedServiceStateListener(this)
                            continuation.resume(Unit)
                        }
                    }
                }
                continuation.invokeOnCancellation { LyriconApp.removeXposedServiceStateListener(listener) }
                LyriconApp.addXposedServiceStateListener(listener)
            }
        }
        return read()
}
