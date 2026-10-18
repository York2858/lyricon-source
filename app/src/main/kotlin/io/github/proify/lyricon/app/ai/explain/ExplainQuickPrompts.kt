package io.github.proify.lyricon.app.ai.explain

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class QuickPromptGroup(val label: String) {
    LINE("这一句"), SONG("整首歌"), LANGUAGE("外语词句")
}

fun quickPromptGroup(range: ExplainRange, angle: ExplainAngle): QuickPromptGroup = when {
    angle == ExplainAngle.LANGUAGE -> QuickPromptGroup.LANGUAGE
    range == ExplainRange.SONG -> QuickPromptGroup.SONG
    else -> QuickPromptGroup.LINE
}

fun defaultQuickPrompts(group: QuickPromptGroup): List<String> = when (group) {
    QuickPromptGroup.LINE -> listOf("这句有什么隐喻？", "这句在全文中重复出现了吗？为什么？", "换个通俗的说法")
    QuickPromptGroup.SONG -> listOf("这首歌的核心意象是什么？", "情绪在哪里发生转折？", "换个通俗的说法")
    QuickPromptGroup.LANGUAGE -> listOf("这里的语气有什么特别？", "直译会丢失什么含义？", "换个通俗的说法")
}

fun cleanQuickPrompts(values: List<String>): List<String> {
    val cleaned = values.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    require(cleaned.size <= ExplainQuickPrompts.MAX_COUNT) { "最多设置 ${ExplainQuickPrompts.MAX_COUNT} 条快捷问题" }
    require(cleaned.all { it.length <= ExplainQuickPrompts.MAX_LENGTH && '\n' !in it && '\r' !in it }) {
        "每条快捷问题最多 ${ExplainQuickPrompts.MAX_LENGTH} 字，且不能包含换行"
    }
    return cleaned
}

/** An explicitly saved empty list hides suggestions; a missing key uses contextual defaults. */
class ExplainQuickPrompts(context: Context) {
    private val prefs = context.getSharedPreferences("ai_explain_quick_prompts", Context.MODE_PRIVATE)

    fun get(group: QuickPromptGroup): List<String> {
        val raw = prefs.getString(group.name, null) ?: return defaultQuickPrompts(group)
        return runCatching { cleanQuickPrompts(Json.decodeFromString<List<String>>(raw)) }
            .getOrElse { defaultQuickPrompts(group) }
    }

    /** Call on an IO dispatcher, so success means the preference has reached disk. */
    fun save(group: QuickPromptGroup, values: List<String>): List<String> {
        val cleaned = cleanQuickPrompts(values)
        check(prefs.edit().putString(group.name, Json.encodeToString(cleaned)).commit()) { "快捷问题保存失败" }
        return cleaned
    }

    fun reset(group: QuickPromptGroup): List<String> {
        check(prefs.edit().remove(group.name).commit()) { "快捷问题恢复失败" }
        return defaultQuickPrompts(group)
    }

    companion object {
        const val MAX_COUNT = 12
        const val MAX_LENGTH = 200
    }
}
