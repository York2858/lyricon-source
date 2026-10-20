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
        texts: List<String>
    ): List<TranslationItem>? {
        if (configs.apiKey.isNullOrBlank()) {
            Log.e(TAG, "Request aborted: API Key is null or blank.")
            return null
        }

        val requestItems = texts.mapIndexedNotNull { index, text ->
            text.trim().takeIf(::shouldRequestTranslation)?.let {
                TranslationRequestItem(index = index, text = it)
            }
        }
        if (requestItems.isEmpty()) {
            Log.d(TAG, "Request skipped: no translatable lyric lines.")
            return emptyList()
        }

        val payload = json.encodeToString(TranslationRequest(requestItems))
        Log.d(TAG, "Requesting translations, payload: $payload")

        val requestIndices = requestItems.map { it.index }.toSet()
        val systemPrompt = AiTranslationPrompt.build(options, song)
        val content = AiTranslationAppClient.complete(configs, systemPrompt, payload)

        Log.d(TAG, "Parsing JSON response: $content")
        val parsed = try { AiTranslationResponseParser.parse(content, requestIndices) }
        catch (_: Exception) { throw TranslationFailure(TranslationDiagnostic("FORMAT", "AI 返回的内容无法解析为歌词译文", "模型需要返回包含 translated、index、tran 的 JSON 数据；检查模型和自定义提示词。")) }
        if (parsed.size != requestIndices.size) throw TranslationFailure(TranslationDiagnostic("INCOMPLETE",
            "AI 返回的译文缺少 ${requestIndices.size - parsed.size} 句", "更换模型或稍后重试；Google 保底开启时会继续补译。"))
        return parsed
    }

    suspend fun requestGoogle(options: AiTranslationOptions, texts: List<String>): List<TranslationItem> {
        val indexed = texts.mapIndexedNotNull { index, text -> text.trim().takeIf(::shouldRequestTranslation)?.let { index to it } }
        if (indexed.isEmpty()) return emptyList()
        val raw = AiTranslationAppClient.google(options.targetLanguage, options.googleBaseUrl, indexed.map { it.second })
        val rows = try { TranslationLibraryContract.json.decodeFromString<List<String>>(raw) }
        catch (_: Exception) { throw TranslationFailure(TranslationDiagnostic("GOOGLE_FORMAT", "Google 译文格式不正确", "检查 Google 接口地址和网络。")) }
        if (rows.size != indexed.size || rows.any { it.isBlank() }) throw TranslationFailure(TranslationDiagnostic("GOOGLE_ALIGNMENT", "Google 译文与原文行数不一致", "稍后重试或检查接口是否兼容 Google 翻译格式。"))
        return indexed.mapIndexed { i, item -> TranslationItem(item.first, rows[i]) }
    }

    /** 只请求包含字母（语言可识别）的行；纯数字/标点/空白行不消耗 API 额度。 */
    private fun shouldRequestTranslation(text: String): Boolean {
        if (text.isBlank()) return false
        return text.any { it.isLetter() }
    }
}
