/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.xposed.systemui.ai.translate

import android.util.Log
import io.github.proify.lyricon.lyric.ai.translate.*
import io.github.proify.android.extensions.json
import io.github.proify.lyricon.lyric.ai.core.AiConfig
import io.github.proify.lyricon.lyric.model.Song

/**
 * 歌词翻译请求组装器。
 *
 * 职责：筛选需要翻译的歌词行 → 组装 JSON 请求体 → 调用共享的 [AiChatClient] →
 * 交给 [AiTranslationResponseParser] 解析。网络与鉴权细节在 [AiChatClient]。
 */
internal object AiTranslationRequester {
    private const val TAG = "LyriconAiTranslator"

    suspend fun request(
        configs: AiConfig,
        options: AiTranslationOptions,
        song: Song? = null,
        texts: List<String>,
        complete: suspend (AiConfig, String, String) -> String = AiTranslationAppClient::complete
    ): List<TranslationItem>? {
        if (configs.apiKey.isNullOrBlank()) {
            Log.e(TAG, "Request aborted: API Key is null or blank.")
            return null
        }

        val requestItems = AiTranslationPolicy.requestItems(texts, options, song)
        if (requestItems.isEmpty()) {
            Log.d(TAG, "Request skipped: no translatable lyric lines.")
            return emptyList()
        }

        val payload = json.encodeToString(TranslationRequest(requestItems))
        Log.d(TAG, "Requesting translations, payload: $payload")

        val systemPrompt = AiTranslationPrompt.build(options, song)
        val content = complete(configs, systemPrompt, payload)

        Log.d(TAG, "Parsing JSON response: $content")
        return AiTranslationResponseParser.parseValidated(content, requestItems)
    }

    suspend fun requestGoogle(options: AiTranslationOptions, texts: List<String>, song: Song? = null,
        translate: suspend (String?, String, List<String>) -> String = AiTranslationAppClient::google): List<TranslationItem> {
        val indexed = AiTranslationPolicy.requestItems(texts, options, song)
        if (indexed.isEmpty()) return emptyList()
        val raw = translate(options.targetLanguage, options.googleBaseUrl, indexed.map { it.text })
        val rows = try { TranslationLibraryContract.json.decodeFromString<List<String>>(raw) }
        catch (_: Exception) { throw TranslationFailure(TranslationDiagnostic("GOOGLE_FORMAT", "Google 译文格式不正确", "检查 Google 接口地址和网络。")) }
        if (rows.size != indexed.size || rows.any { it.isBlank() }) throw TranslationFailure(TranslationDiagnostic("GOOGLE_ALIGNMENT", "Google 译文与原文行数不一致", "稍后重试或检查接口是否兼容 Google 翻译格式。"))
        if (indexed.indices.any { !isUsableLyricTranslation(indexed[it].text, rows[it]) })
            throw TranslationFailure(TranslationDiagnostic("GOOGLE_UNCHANGED", "Google 返回了与原文相同的内容，未产生有效翻译",
                "检查 Google 接口和目标语言，稍后重试；原文不会作为译文保存。"))
        return indexed.mapIndexed { i, item -> TranslationItem(item.index, rows[i].trim()) }
    }
}
