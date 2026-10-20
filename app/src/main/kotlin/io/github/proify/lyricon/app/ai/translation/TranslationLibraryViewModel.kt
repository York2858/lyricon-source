package io.github.proify.lyricon.app.ai.translation

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.proify.lyricon.lyric.ai.translate.*
import kotlinx.coroutines.*

internal class TranslationLibraryViewModel @JvmOverloads constructor(application: Application,
    private val repository: TranslationLibraryRepository = SystemTranslationLibrary(application)
) : AndroidViewModel(application) {
    var query by mutableStateOf(TranslationLibraryQuery(status = "translated")); private set
    var page by mutableStateOf<TranslationLibraryPage?>(null); private set
    var songs by mutableStateOf(emptyList<TranslationSongEntry>()); private set
    var group by mutableStateOf<TranslationSongEntry?>(null); private set
    var versions by mutableStateOf(emptyList<TranslationVersion>()); private set
    var version by mutableStateOf<TranslationVersion?>(null); private set
    var lines by mutableStateOf(emptyList<TranslationLine>()); private set
    var moreVersions by mutableStateOf(false); private set
    var nextLine by mutableStateOf<Int?>(null); private set
    var selected by mutableStateOf(emptySet<String>()); private set
    var selecting by mutableStateOf(false)
    var loading by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var notice by mutableStateOf<String?>(null); private set
    private var job: Job? = null
    private var revision = 0
    private val musicFolders = MusicFolderExport(application)
    var musicFolderRevision by mutableIntStateOf(0); private set
    var musicOverwrite by mutableStateOf<PendingMusicFolderWrite?>(null); private set
    var musicLocation by mutableStateOf<TranslationMusicLocation?>(null); private set
    var locatingMusic by mutableStateOf(false); private set
    private var locationVersionId: String? = null
    private var locationJob: Job? = null
    private var locationGeneration = 0
    var batchProgress by mutableStateOf<BatchExportProgress?>(null); private set
    var batchResult by mutableStateOf<BatchExportResult?>(null); private set
    private var stopBatch = false

    init { refresh() }
    fun clearNotice() { notice = null }
    fun toggle(key: String) {
        selected = if (key in selected) selected - key else if (selected.size < 100) selected + key else {
            notice = "每次最多选择 100 首歌曲"; selected
        }
    }
    fun selectVisible() { selected = songs.take(100).map { it.key }.toSet() }
    fun selectAllResults() = mutate {
        val filter = query
        val all = mutableListOf<TranslationSongEntry>(); var offset = 0
        do {
            val result = repository.list(filter.copy(offset = offset))
            all += result.songs; page = result
            if (!result.hasMore || all.size >= 100) break
            check(result.songs.isNotEmpty()) { "读取进度异常，请重试" }; offset += result.songs.size
        } while (true)
        if (query != filter) return@mutate
        songs = all.distinctBy { it.key }; selected = songs.take(100).map { it.key }.toSet()
        if ((page?.totalSongs ?: 0) > 100) notice = "已选择前 100 首，请分批导出"
    }
    fun endSelection() { selected = emptySet(); selecting = false }
    fun updateQuery(value: TranslationLibraryQuery) {
        query = value.copy(offset = 0); selected = emptySet(); page = null; songs = emptyList()
        load(debounce = true) { loadSongs(false) }
    }
    private fun load(debounce: Boolean = false, action: suspend () -> Unit) {
        job?.cancel(); val current = ++revision
        loading = true; error = null
        job = viewModelScope.launch {
            try { if (debounce) delay(300); action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (current == revision) error = e.message ?: "读取失败，请重试" }
            finally { if (current == revision) loading = false }
        }
    }
    fun refresh() = load {
        when {
            version != null && version?.status != "translated" -> refreshOutcome()
            version != null -> loadLines(false)
            group != null -> loadVersions(false)
            else -> loadSongs(false)
        }
    }
    private suspend fun refreshOutcome() {
        val id = checkNotNull(version).id
        var offset = 0
        do {
            val result = repository.versions(checkNotNull(group).key, offset, query.status)
            result.versions.firstOrNull { it.id == id }?.let { version = it; return }
            if (!result.hasMore) break
            check(result.versions.isNotEmpty()) { "读取进度异常，请重试" }
            offset += result.versions.size
        } while (true)
        error("这条记录已被删除，请返回刷新列表")
    }
    fun more() = load {
        when { version != null -> loadLines(true); group != null -> loadVersions(true); else -> loadSongs(true) }
    }
    private suspend fun loadSongs(append: Boolean) {
        val result = repository.list(query.copy(offset = if (append) songs.size else 0))
        page = result
        songs = (if (append) songs + result.songs else result.songs).distinctBy { it.key }
        selected = selected.intersect(songs.map { it.key }.toSet())
    }
    fun openSong(value: TranslationSongEntry) {
        group = value; version = null; versions = emptyList(); moreVersions = false
        load { loadVersions(false) }
    }
    private suspend fun loadVersions(append: Boolean) {
        val result = repository.versions(checkNotNull(group).key, if (append) versions.size else 0, query.status)
        versions = (if (append) versions + result.versions else result.versions).distinctBy { it.id }
        moreVersions = result.hasMore
    }
    fun openVersion(value: TranslationVersion) {
        version = value; lines = emptyList(); nextLine = null
        load { loadLines(false) }
    }
    private suspend fun loadLines(append: Boolean) {
        val id = checkNotNull(version).id
        val result = repository.lines(id, if (append) nextLine ?: return else 0)
        lines = if (append) lines + result.lines else result.lines
        nextLine = result.nextOffset
        // A single song is fetched in bounded messages, then searched in its entirety.
        while (nextLine != null) {
            val offset = nextLine!!
            val more = repository.lines(id, offset)
            val following = more.nextOffset
            check(following == null || following > offset) { "读取进度异常，请重试" }
            lines = lines + more.lines; nextLine = more.nextOffset
        }
    }
    fun back(): Boolean {
        when {
            version != null -> { version = null; lines = emptyList(); refresh() }
            group != null -> { group = null; versions = emptyList(); refresh() }
            selecting -> endSelection()
            else -> return false
        }
        return true
    }
    private fun mutate(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { notice = e.message ?: "操作失败，请重试" }
            finally { busy = false }
        }
    }
    fun favorite(value: TranslationVersion) = mutate {
        repository.favorite(value.id, !value.favorite)
        versions = versions.map { if (it.id == value.id) it.copy(favorite = !value.favorite) else it }
        if (version?.id == value.id) version = version?.copy(favorite = !value.favorite)
        if (versions.any { it.id == value.id }) group = group?.let {
            it.copy(favorites = (it.favorites + if (value.favorite) -1 else 1).coerceAtLeast(0))
        }
        notice = if (value.favorite) "已取消收藏" else "已收藏此翻译版本"
    }
    fun delete(ids: List<String>, groups: List<String>) = mutate {
        val affectedGroup = group?.key
        val removed = versions.filter { it.id in ids }
        val count = repository.delete(ids, groups, query.status)
        job?.cancel(); revision++; loading = false
        if (groups.isNotEmpty()) {
            if (group?.key in groups) { group = null; version = null }
            endSelection()
        }
        if (version?.id in ids) version = null
        if (groups.isEmpty() && group?.key == affectedGroup) group = group?.let { song -> song.copy(
            versions = (song.versions - count).coerceAtLeast(0),
            lines = (song.lines - removed.sumOf { it.lineCount }).coerceAtLeast(0),
            favorites = (song.favorites - removed.count { it.favorite }).coerceAtLeast(0)) }
        notice = "已删除 $count 条记录"
        refresh()
    }
    private suspend fun fullLines(value: TranslationVersion): List<TranslationLine> {
        val all = mutableListOf<TranslationLine>(); var offset = 0
        do {
            val result = repository.lines(value.id, offset)
            all += result.lines
            val next = result.nextOffset
            if (next == null) break
            check(next > offset) { "读取进度异常，请重试" }; offset = next
        } while (true)
        return all
    }
    fun copy(noticeText: String = "已复制完整双语歌词", onReady: (String) -> Unit) {
        val value = version ?: return
        mutate { onReady(translationText(value, fullLines(value))); notice = noticeText }
    }
    fun export(uri: Uri, value: TranslationVersion, options: TranslationExportOptions = TranslationExportOptions()) = mutate {
        val all = fullLines(value)
        withContext(Dispatchers.IO) {
            val text = exportTranslationLyrics(value, all, options)
            checkNotNull(getApplication<Application>().contentResolver.openOutputStream(uri)) { "无法写入文件" }
                .bufferedWriter(Charsets.UTF_8).use { it.write(text) }
        }
        notice = "已导出${options.format.label}（${options.content.label}）"
    }

    private fun savedMusicFolder(value: TranslationVersion, location: TranslationMusicLocation?): MusicFolder? =
        musicFolders.saved(value)?.let { musicFolders.targetWithin(it, location) }

    fun savedMusicFolder(value: TranslationVersion): MusicFolder? = savedMusicFolder(value,
        musicLocation.takeIf { locationVersionId == value.id })

    fun locateMusicFolder(value: TranslationVersion) {
        locationJob?.cancel()
        val generation = ++locationGeneration
        locationVersionId = value.id; musicLocation = null; locatingMusic = true
        locationJob = viewModelScope.launch {
            try { musicLocation = readMusicLocation(value) }
            finally { if (generation == locationGeneration) locatingMusic = false }
        }
    }

    private suspend fun readMusicLocation(value: TranslationVersion): TranslationMusicLocation = try {
        repository.musicLocation(value.id)
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { TranslationMusicLocation(message = "暂时无法自动定位，可手动选择；更新模块后需重启系统界面") }

    private suspend fun musicLocationFor(value: TranslationVersion): TranslationMusicLocation {
        if (locationVersionId == value.id) {
            locationJob?.join()
            if (locationVersionId == value.id) musicLocation?.let { return it }
        }
        return readMusicLocation(value)
    }

    fun prepareSaveAs(value: TranslationVersion, options: TranslationExportOptions, onReady: (String) -> Unit) = mutate {
        val location = musicLocationFor(value)
        onReady(translationExportFileName(value, options, location.audioFileName))
    }

    fun detectedMusicFolder(): MusicFolder? = musicLocation?.let(musicFolders::detected)
    fun hasMusicFolderAccess(folder: MusicFolder): Boolean = musicFolders.hasPersistentWriteAccess(folder)

    fun chooseMusicFolder(value: TranslationVersion, onChoose: (Uri?) -> Unit) = mutate {
        val location = musicLocationFor(value)
        onChoose(savedMusicFolder(value, location)?.uri ?: musicFolders.detected(location)?.uri)
    }

    fun rememberMusicFolder(value: TranslationVersion, uri: Uri) = mutate {
        val location = musicLocationFor(value)
        val folder = withContext(Dispatchers.IO) { musicFolders.targetWithin(musicFolders.describe(uri), location) }
        check(musicFolders.hasPersistentWriteAccess(folder)) { "未获得长期读写授权，请重新选择文件夹" }
        musicFolders.remember(value, folder)
        musicFolderRevision++
        notice = "已记住音乐文件夹：${folder.name}"
    }

    fun exportToMusicFolder(value: TranslationVersion, options: TranslationExportOptions,
        selectedFolder: Uri? = null, forceChoose: Boolean = false, onChooseFolder: (Uri?) -> Unit = {}) = mutate {
        val location = musicLocationFor(value)
        val saved = savedMusicFolder(value, location) ?: musicFolders.detected(location)
        if (selectedFolder == null && (forceChoose || saved == null || !musicFolders.hasPersistentWriteAccess(saved))) {
            onChooseFolder(saved?.uri)
            return@mutate
        }
        val folder = withContext(Dispatchers.IO) {
            if (selectedFolder != null) musicFolders.targetWithin(musicFolders.describe(selectedFolder), location) else checkNotNull(saved)
        }
        val all = fullLines(value)
        val text = withContext(Dispatchers.IO) { exportTranslationLyrics(value, all, options) }
        writeMusicFolder(PendingMusicFolderWrite(value, options, folder,
            translationExportFileName(value, options, location.audioFileName), text), MusicFileWriteMode.ASK)
    }

    fun dismissMusicOverwrite() { musicOverwrite = null }
    fun selectedBatchSongs(): List<TranslationSongEntry> = songs.filter { it.key in selected }.take(100)
    fun stopBatchExport() { stopBatch = true }
    fun dismissBatchResult() { batchResult = null }
    fun folderGrantFailed(message: String) { notice = message }
    fun rememberBatchMusicGrant(uri: Uri) = mutate {
        val folder = withContext(Dispatchers.IO) { musicFolders.describe(uri) }
        check(musicFolders.hasPersistentWriteAccess(folder)) { "未获得完整读写授权，请重新选择文件夹" }
        musicFolderRevision++; notice = "已授权：${folder.name}，其下的音乐目录可直接导出"
    }
    fun exportBatch(values: List<TranslationSongEntry>, options: BatchExportOptions, destination: Uri? = null) = mutate {
        stopBatch = false; batchResult = null
        batchProgress = BatchExportProgress(0, values.size, "准备导出")
        try {
            batchResult = TranslationBatchExporter(getApplication(), repository).export(values, options, destination,
                shouldStop = { stopBatch }, onProgress = { batchProgress = it })
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { batchResult = BatchExportResult(listOf(BatchExportItem("批量导出", false, e.message ?: "写入失败，请更换保存位置")), values.size) }
        finally { batchProgress = null; musicFolderRevision++ }
    }
    fun confirmMusicOverwrite(copy: Boolean) {
        val request = musicOverwrite ?: return
        musicOverwrite = null
        mutate { writeMusicFolder(request, if (copy) MusicFileWriteMode.COPY else MusicFileWriteMode.REPLACE) }
    }

    private suspend fun writeMusicFolder(request: PendingMusicFolderWrite, mode: MusicFileWriteMode) {
        val result = withContext(Dispatchers.IO) {
            musicFolders.write(request.folder, request.name, request.options.format.mimeType, request.text, mode)
        }
        when (result) {
            is MusicFolderWriteResult.Exists -> musicOverwrite = request.copy(name = result.name)
            is MusicFolderWriteResult.Saved -> {
                musicFolders.remember(request.version, request.folder)
                musicFolderRevision++
                notice = "已保存到 ${request.folder.name}/${result.name}" +
                    if (musicFolders.hasPersistentWriteAccess(request.folder)) "" else "（下次需重新选择文件夹）"
            }
        }
    }
}

internal data class PendingMusicFolderWrite(val version: TranslationVersion, val options: TranslationExportOptions,
    val folder: MusicFolder, val name: String, val text: String)
