package io.github.proify.lyricon.lyric.ai.translate

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Small, paged messages shared by the app and the SystemUI database owner. */
object TranslationLibraryContract {
    const val ROUTE = "translation_library_endpoint"
    const val LIST = 1
    const val VERSIONS = 2
    const val LINES = 3
    const val FAVORITE = 4
    const val DELETE = 5
    const val MUSIC_LOCATION = 6
    const val LINE_RECORD_SUMMARY = 7
    const val DELETE_LINE_RECORDS = 8
    const val PAGE_SIZE = 30
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
}

@Serializable
data class TranslationLibraryQuery(
    val search: String = "", val language: String = "", val favoritesOnly: Boolean = false,
    val sort: String = "newest", val offset: Int = 0, val status: String = "",
    // Filter the latest timestamp of each song in the selected status, [from, before).
    val updatedFrom: Long? = null, val updatedBefore: Long? = null,
)

@Serializable
data class TranslationSongEntry(
    val key: String, val title: String, val artist: String, val versions: Int,
    val lines: Int, val favorites: Int, val updatedAt: Long, val languages: String,
    val skipped: Int = 0, val errors: Int = 0, val translated: Int = 0,
    val wordTimedVersions: Int = 0, val lineTimedVersions: Int = 0,
)

@Serializable
data class TranslationLibraryPage(
    val songs: List<TranslationSongEntry>, val hasMore: Boolean,
    val totalSongs: Int, val totalVersions: Int, val totalLines: Int,
    val totalFavorites: Int, val languages: List<String>,
    val totalSkipped: Int = 0, val totalErrors: Int = 0,
)

@Serializable
data class TranslationVersion(
    val id: String, val title: String, val artist: String, val language: String,
    val model: String, val createdAt: Long, val lineCount: Int, val favorite: Boolean,
    val hasOriginal: Boolean,
    val hasTiming: Boolean = false, val hasWordTiming: Boolean = false,
    val status: String = "translated", val reason: String = "", val suggestion: String = "",
    val resolution: String = "", val eventCount: Int = 1, val source: String = "ai",
)

@Serializable
data class TranslationVersionPage(val versions: List<TranslationVersion>, val hasMore: Boolean)

@Serializable
data class TranslationLineRecordSummary(val count: Int, val favorites: Int)

@Serializable
data class TranslationLine(val index: Int, val original: String = "", val translation: String,
    val begin: Long? = null, val end: Long? = null, val words: List<TranslationWord> = emptyList())

@Serializable
data class TranslationWord(val text: String, val begin: Long, val end: Long)

@Serializable
data class TranslationTiming(val index: Int, val begin: Long, val end: Long,
    val words: List<TranslationWord> = emptyList())

@Serializable
data class TranslationLinePage(val lines: List<TranslationLine>, val nextOffset: Int?)

/** A verified local file's parent; finding it does not grant the app write access. */
@Serializable
data class TranslationMusicLocation(val folderUri: String = "", val folderName: String = "",
    val audioFileName: String = "", val message: String = "未找到对应的本地音乐，可手动选择文件夹")

fun translationText(version: TranslationVersion, lines: List<TranslationLine>): String = buildString {
    appendLine(version.title.ifBlank { "未知歌曲（旧记录）" })
    if (version.artist.isNotBlank()) appendLine(version.artist)
    appendLine("目标语言：${version.language.ifBlank { "未记录" }}")
    if (version.model.isNotBlank()) appendLine("翻译模型：${version.model}")
    appendLine()
    lines.forEach {
        if (it.original.isNotBlank()) appendLine(it.original)
        appendLine(it.translation)
        appendLine()
    }
}.trim()
