package io.github.proify.lyricon.app.ai.translation

import android.content.Context
import android.net.Uri
import io.github.proify.lyricon.lyric.ai.translate.*
import kotlinx.coroutines.*
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal enum class BatchExportDestination(val label: String) { MUSIC("音乐所在文件夹"), FOLDER("统一文件夹"), ZIP("打包 ZIP") }
internal enum class BatchDuplicatePolicy(val label: String) { SKIP("跳过同名文件"), COPY("另存一份"), REPLACE("覆盖已有文件") }
internal data class BatchExportOptions(val lyrics: TranslationExportOptions = TranslationExportOptions(),
    val destination: BatchExportDestination = BatchExportDestination.ZIP,
    val duplicates: BatchDuplicatePolicy = BatchDuplicatePolicy.SKIP)
internal data class BatchExportItem(val song: String, val success: Boolean, val detail: String, val skipped: Boolean = false)
internal data class BatchExportProgress(val completed: Int, val total: Int, val song: String)
internal data class BatchExportResult(val items: List<BatchExportItem>, val total: Int, val cancelled: Boolean = false) {
    val succeeded get() = items.count { it.success }
    val skipped get() = items.count { it.skipped }
    val failed get() = items.count { !it.success && !it.skipped }
}

/** Bounded reads and one song at a time; a failed song never aborts other songs. */
internal class TranslationBatchExporter(private val context: Context, private val repository: TranslationLibraryRepository) {
    private val folders = MusicFolderExport(context)
    suspend fun export(songs: List<TranslationSongEntry>, options: BatchExportOptions, destination: Uri?,
        shouldStop: () -> Boolean, onProgress: suspend (BatchExportProgress) -> Unit): BatchExportResult {
        require(songs.isNotEmpty() && songs.size <= 100) { "每次请选择 1 至 100 首歌曲" }
        val results = mutableListOf<BatchExportItem>()
        val written = mutableSetOf<String>()
        var cancelled = false
        val zip = if (options.destination == BatchExportDestination.ZIP) withContext(Dispatchers.IO) {
            ZipOutputStream(checkNotNull(context.contentResolver.openOutputStream(checkNotNull(destination), "wt")))
        } else null
        try {
            val unified = if (options.destination == BatchExportDestination.FOLDER) withContext(Dispatchers.IO) {
                folders.describe(checkNotNull(destination) { "请先选择导出文件夹" })
            } else null
            for ((position, song) in songs.withIndex()) {
                currentCoroutineContext().ensureActive()
                if (shouldStop()) { cancelled = true; break }
                val label = listOf(song.artist, song.title.ifBlank { "未知歌曲" }).filter { it.isNotBlank() }.joinToString(" - ")
                onProgress(BatchExportProgress(position, songs.size, label))
                try {
                    val version = repository.versions(song.key, 0).versions.firstOrNull() ?: error("这首歌已没有翻译记录")
                    val text = withContext(Dispatchers.IO) { exportTranslationLyrics(version, readAllLines(version.id), options.lyrics) }
                    val location = try { repository.musicLocation(version.id) }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { TranslationMusicLocation() }
                    val name = translationExportFileName(version, options.lyrics, location.audioFileName)
                    val detail = withContext(Dispatchers.IO) {
                        if (zip != null) {
                            val directory = label.map { if (it in "\\/:*?\"<>|" || it.isISOControl()) '_' else it }
                                .joinToString("").trim().take(100).trimEnd('.', ' ').ifBlank { "lyrics" }
                            val entry = uniqueEntry("$directory/$name", written)
                            zip.putNextEntry(ZipEntry(entry)); zip.write(text.toByteArray(Charsets.UTF_8)); zip.closeEntry()
                            entry
                        } else {
                            val folder = unified ?: (folders.saved(version)?.let { folders.targetWithin(it, location) } ?: folders.detected(location))
                                ?: error("未找到音乐目录，可改用统一文件夹或 ZIP")
                            if (unified == null) check(folders.hasPersistentWriteAccess(folder)) { "音乐目录尚未授权，请先授权 Music 或对应子文件夹" }
                            val key = "${folder.uri}/$name".lowercase(Locale.ROOT)
                            val mode = when (options.duplicates) {
                                BatchDuplicatePolicy.SKIP -> MusicFileWriteMode.ASK
                                BatchDuplicatePolicy.COPY -> MusicFileWriteMode.COPY
                                BatchDuplicatePolicy.REPLACE -> if (key in written) MusicFileWriteMode.COPY else MusicFileWriteMode.REPLACE
                            }
                            when (val saved = folders.write(folder, name, options.lyrics.format.mimeType, text, mode)) {
                                is MusicFolderWriteResult.Exists -> throw ExistingBatchFile("${folder.name}/${saved.name}")
                                is MusicFolderWriteResult.Saved -> {
                                    written += key
                                    if (unified == null) folders.remember(version, folder)
                                    "${folder.name}/${saved.name}"
                                }
                            }
                        }
                    }
                    results += BatchExportItem(label, true, detail)
                } catch (e: CancellationException) { throw e }
                catch (e: ExistingBatchFile) { results += BatchExportItem(label, false, "已有同名文件：${e.message}", true) }
                catch (e: Exception) { results += BatchExportItem(label, false, e.message ?: "导出失败") }
                onProgress(BatchExportProgress(position + 1, songs.size, label))
            }
            if (zip != null) withContext(Dispatchers.IO) {
                zip.putNextEntry(ZipEntry("导出报告.txt"))
                zip.write(buildString {
                    appendLine("所选 ${songs.size} 首，每首导出最新翻译版本")
                    if (cancelled) appendLine("已停止，尚未处理的歌曲未导出")
                    results.forEach { appendLine("${if (it.success) "成功" else if (it.skipped) "跳过" else "失败"} | ${it.song} | ${it.detail}") }
                }.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            }
        } finally { withContext(NonCancellable + Dispatchers.IO) { zip?.close() } }
        return BatchExportResult(results, songs.size, cancelled)
    }
    private suspend fun readAllLines(id: String): List<TranslationLine> {
        val all = mutableListOf<TranslationLine>(); var offset = 0
        do {
            val result = repository.lines(id, offset); all += result.lines
            val next = result.nextOffset ?: break
            check(next > offset) { "读取进度异常，请重试" }; offset = next
        } while (true)
        return all
    }
    private fun uniqueEntry(name: String, used: MutableSet<String>): String {
        var candidate = name; var index = 1
        while (!used.add(candidate.lowercase(Locale.ROOT))) candidate = "${name.substringBeforeLast('.')} (${index++}).${name.substringAfterLast('.')}"
        return candidate
    }
    private class ExistingBatchFile(path: String) : Exception(path)
}
