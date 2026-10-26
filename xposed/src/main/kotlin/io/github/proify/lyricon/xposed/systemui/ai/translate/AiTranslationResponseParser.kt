/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.xposed.systemui.ai.translate

import io.github.proify.android.extensions.json
import io.github.proify.lyricon.lyric.ai.translate.TranslationDiagnostic
import io.github.proify.lyricon.lyric.ai.translate.TranslationFailure
import io.github.proify.lyricon.lyric.ai.translate.isUsableLyricTranslation

/**
 * LLM 响应清洗与兼容解析：
 * - 兼容 LLM 包裹 ```json 代码块等脏输出；
 * - 只接受请求索引内的有效译文（去重、去空白、拒绝空值）；
 * - 结果按 index 升序排列（提示词协议约定"使用原 index，升序"）。
 */
internal object AiTranslationResponseParser {
    fun parseValidated(content: String, requests: List<TranslationRequestItem>): List<TranslationItem> {
        val response = try { decodeResponse(content) }
        catch (_: Exception) { throw TranslationFailure(TranslationDiagnostic("FORMAT", "AI 返回的内容无法解析为歌词译文",
            "模型需要返回 translated（index、tran）和可选 skipped 索引数组；检查模型和自定义提示词。")) }
        val sources = requests.associate { it.index to it.text }
        val items = normalizeTranslationItems(response.translated, sources.keys)
        if (items.any { !isUsableLyricTranslation(sources[it.index], it.translation) })
            throw TranslationFailure(TranslationDiagnostic("UNCHANGED", "AI 返回了与原文相同的内容，未产生有效翻译",
                "检查目标语言和模型；开启 Google 同步翻译时会尝试使用有效的 Google 译文。"))
        val skipped = response.skipped.filter { it in sources }.toSet()
        if (items.any { it.index in skipped }) throw TranslationFailure(TranslationDiagnostic("FORMAT",
            "AI 将同一行同时标记为已翻译和跳过", "每个 index 只能出现在 translated 或 skipped 之一。"))
        val missing = sources.keys - items.map { it.index }.toSet() - skipped
        if (missing.isNotEmpty()) throw TranslationFailure(TranslationDiagnostic("INCOMPLETE",
            "AI 返回的译文缺少 ${missing.size} 句", "更换模型或稍后重试；无需翻译的行应明确放入 skipped 数组。"))
        return items
    }

    private fun decodeResponse(content: String): TranslationResponse {
        return try {
            json.decodeFromString<TranslationResponse>(content)
        } catch (_: Exception) {
            json.decodeFromString<TranslationResponse>(cleanOpenAIResponse(content))
        }
    }

    private fun cleanOpenAIResponse(rawResponse: String): String {
        return rawResponse
            .replace(Regex("^```json\\s*\\n?"), "")  // 移除开头的 ```json
            .replace(Regex("^```\\s*\\n?"), "")       // 移除开头的 ```
            .replace(Regex("\\n?```\\s*$"), "")       // 移除结尾的 ```
            .trim()
    }

    private fun normalizeTranslationItems(
        items: List<TranslationItem>,
        requestIndices: Set<Int>
    ): List<TranslationItem> {
        val accepted = LinkedHashMap<Int, TranslationItem>()
        items.forEach { item ->
            val translation = item.translation.trim()
            if (item.index in requestIndices && translation.isNotBlank() && item.index !in accepted) {
                accepted[item.index] = item.copy(translation = translation)
            }
        }
        return accepted.values.sortedBy { it.index }
    }
}
