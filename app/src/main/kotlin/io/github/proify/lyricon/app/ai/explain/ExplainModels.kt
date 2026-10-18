package io.github.proify.lyricon.app.ai.explain

import io.github.proify.lyricon.lyric.ai.core.AiChatMessage
import io.github.proify.lyricon.lyric.ai.core.AiConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.Locale

@Serializable
data class ExplainLine(val text: String, val start: Long = -1, val end: Long = -1)

@Serializable
data class ExplainSong(
    val title: String = "", val artist: String = "", val album: String = "",
    val lines: List<ExplainLine> = emptyList(), val coverBase64: String? = null
) {
    val lyrics: String get() = lines.joinToString("\n") { it.text }
    fun sameTrack(other: ExplainSong): Boolean =
        title == other.title && artist == other.artist && lines == other.lines
}

@Serializable
enum class ExplainRange(val label: String) { LINE("这一句"), SONG("整首歌") }

@Serializable
enum class ExplainAngle(val label: String, val instruction: String) {
    PHRASE("词句解读", "聚焦指定的这一句，结合上下文解释字面含义、情绪和表达方式，不扩写成整首乐评。"),
    OVERVIEW("整体赏析", "围绕整首歌词的主题与情绪写一篇自然流畅的整体赏析。"),
    VERSES("逐段解读", "按歌词自然段与叙事变化逐段解读；没有段落信息时按语义分段，不臆造主歌、副歌结构。"),
    IMAGERY("意象与隐喻", "分析解读范围内的意象、隐喻与可能的多义，引用原句并区分文本依据和个人推测。"),
    LANGUAGE("外语词句", "解释解读范围内的外语词句、语气、双关与文化含义，提供译意；没有外语时如实说明，不虚构。")
}

fun anglesFor(range: ExplainRange): List<ExplainAngle> = if (range == ExplainRange.LINE)
    listOf(ExplainAngle.PHRASE, ExplainAngle.IMAGERY, ExplainAngle.LANGUAGE)
else listOf(ExplainAngle.OVERVIEW, ExplainAngle.VERSES, ExplainAngle.IMAGERY, ExplainAngle.LANGUAGE)

@Serializable
enum class ExplainStatus { GENERATING, COMPLETE, STOPPED, ERROR }

@Serializable
data class ExplainTurn(
    val id: String, val question: String = "", val content: String = "", val reasoning: String = "",
    val status: ExplainStatus = ExplainStatus.GENERATING
)

@Serializable
data class ExplainRecord(
    val id: String, val song: ExplainSong, val range: ExplainRange, val angle: ExplainAngle,
    val lineIndex: Int, val turns: List<ExplainTurn>, val createdAt: Long,
    val updatedAt: Long = createdAt, val favorite: Boolean = false
)

data class ExplainSongHistory(val key: String, val song: ExplainSong, val records: List<ExplainRecord>)

/** Group the view, preserving every saved version. Album availability can vary between launches. */
fun groupExplainHistory(records: List<ExplainRecord>, favoritesOnly: Boolean = false): List<ExplainSongHistory> {
    fun normalized(value: String) = value.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
    fun key(song: ExplainSong): String {
        val title = normalized(song.title)
        val identity = if (title.isBlank() || title in listOf("未知歌曲", "unknown song", "unknown"))
            listOf("lyrics", normalized(song.artist), song.lyrics)
        else listOf("song", title, normalized(song.artist))
        return MessageDigest.getInstance("SHA-256").digest(Json.encodeToString(identity).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
    return records.asSequence().filter { !favoritesOnly || it.favorite }.sortedByDescending { it.updatedAt }
        .groupBy { key(it.song) }.map { (key, versions) ->
            val latest = versions.first().song
            ExplainSongHistory(key, latest.copy(
                album = versions.firstOrNull { it.song.album.isNotBlank() }?.song?.album.orEmpty(),
                coverBase64 = versions.firstOrNull { it.song.coverBase64 != null }?.song?.coverBase64
            ), versions)
        }
}

/** Favorites are versioned snapshots: continuing a saved conversation creates a new record. */
fun ExplainRecord.forContinuation(newId: String): ExplainRecord =
    if (favorite) copy(id = newId, favorite = false) else this

fun conversationFor(turns: List<ExplainTurn>, question: String): List<AiChatMessage> = buildList {
    turns.forEach { turn ->
        if (turn.content.isNotBlank()) {
            if (turn.question.isNotBlank()) add(AiChatMessage(turn.question))
            add(AiChatMessage(turn.content, assistant = true))
        }
    }
    add(AiChatMessage(question))
}

/** JSON avoids delimiter collisions, including repeated lyric text at different positions. */
fun explainCacheKey(
    song: ExplainSong, range: ExplainRange, angle: ExplainAngle, lineIndex: Int,
    config: AiConfig, language: String, systemPrompt: String
): String {
    val values = listOf("explain-v2", song.title, song.artist, song.album, song.lyrics,
        range.name, angle.name, (if (range == ExplainRange.LINE) lineIndex else -1).toString(),
        config.baseUrl.orEmpty(), config.model.orEmpty(),
        config.temperature.toString(), config.topP.toString(), config.presencePenalty.toString(),
        config.frequencyPenalty.toString(), language, systemPrompt)
    return MessageDigest.getInstance("SHA-256").digest(Json.encodeToString(values).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
