/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.xposed.systemui.ai.translate

import android.util.Log
import io.github.proify.lyricon.lyric.ai.translate.*
import io.github.proify.android.extensions.json
import io.github.proify.lyricon.lyric.ai.core.AiChatClient
import io.github.proify.lyricon.lyric.ai.core.AiConfig
import io.github.proify.lyricon.lyric.model.Song
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 歌词翻译请求组装器。
 *
 * 职责：筛选需要翻译的歌词行 → 组装 JSON 请求体 → 调用共享的 [AiChatClient] →
 * 交给 [AiTranslationResponseParser] 解析。网络与鉴权细节在 [AiChatClient]。
 */
internal object AiTranslationRequester {
    private const val TAG = "LyriconAiTranslator"

    /** 流式请求的总时限，超时后交给无密钥兜底。 */
    private const val STREAM_TIMEOUT_MS = 120_000L

    /** 增量译文的刷新间隔，避免每行都触发一次视图与播放器元数据更新。 */
    private const val PARTIAL_UPDATE_INTERVAL_MS = 300L

    suspend fun request(
        configs: AiConfig,
        options: AiTranslationOptions,
        song: Song? = null,
        texts: List<String>,
        complete: suspend (AiConfig, String, String) -> String = { config, systemPrompt, payload ->
            Log.i(TAG, "AI request via SystemUI direct connection")
            AiChatClient.completeOrThrow(config, systemPrompt, payload, jsonObject = true)
        }
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
        translate: suspend (String?, String, List<String>) -> String = { language, baseUrl, lines ->
            Log.i(TAG, "Google request via SystemUI direct connection")
            TranslationLibraryContract.json.encodeToString(GoogleLyricsTranslator.translate(lines, language, baseUrl))
        }): List<TranslationItem> {
        val indexed = AiTranslationPolicy.requestItems(texts, options, song)
        if (indexed.isEmpty()) return emptyList()
        val raw = translate(options.targetLanguage, options.googleBaseUrl, indexed.map { it.text })
        val rows = try { TranslationLibraryContract.json.decodeFromString<List<String>>(raw) }
        catch (_: Exception) { throw TranslationFailure(TranslationDiagnostic("GOOGLE_FORMAT", "Google 译文格式不正确", "检查 Google 接口地址和网络。")) }
        if (rows.size != indexed.size || rows.any { it.isBlank() }) throw TranslationFailure(TranslationDiagnostic("GOOGLE_ALIGNMENT", "Google 译文与原文行数不一致", "稍后重试或检查接口是否兼容 Google 翻译格式。"))
        return acceptTranslatedLines("Google", indexed, rows, "GOOGLE_UNCHANGED",
            "Google 返回的内容全部与原文相同，未产生有效翻译",
            "检查 Google 接口和目标语言，稍后重试；个别未变化的行会保留原文。")
    }

    /**
     * 流式 AI 翻译。收到第一段**正文**增量才视为可交付（[onHealth]），
     * 只有思考内容不算；解析出的完整译文行会通过 [onPartial] 增量展示。
     */
    suspend fun requestStreaming(
        configs: AiConfig,
        options: AiTranslationOptions,
        song: Song?,
        texts: List<String>,
        onHealth: () -> Unit,
        onPartial: (List<TranslationItem>) -> Unit
    ): List<TranslationItem>? {
        if (configs.apiKey.isNullOrBlank()) {
            Log.e(TAG, "Request aborted: API Key is null or blank.")
            return null
        }
        val requestItems = AiTranslationPolicy.requestItems(texts, options, song)
        if (requestItems.isEmpty()) return emptyList()

        val payload = json.encodeToString(TranslationRequest(requestItems))
        val systemPrompt = AiTranslationPrompt.build(options, song)
        val healthy = AtomicBoolean(false)
        fun markHealthy() {
            if (healthy.compareAndSet(false, true)) onHealth()
        }
        val parser = StreamingTranslationParser(
            allowed = requestItems.map { it.index }.toSet(),
            sources = requestItems.associate { it.index to it.text }
        )
        var lastPartialAt = 0L

        Log.d(TAG, "Requesting streaming translations, payload: $payload")
        val result = try {
            withTimeout(STREAM_TIMEOUT_MS) {
                AiChatClient.stream(
                    configs = configs,
                    systemPrompt = systemPrompt,
                    userPrompt = payload,
                    onReasoning = {
                        // Reasoning alone is not deliverable; keep waiting for the answer body.
                    },
                    onContent = { delta ->
                        if (delta.isNotEmpty()) markHealthy()
                        parser.append(delta)?.let { items ->
                            val now = System.currentTimeMillis()
                            if (now - lastPartialAt >= PARTIAL_UPDATE_INTERVAL_MS) {
                                lastPartialAt = now
                                onPartial(items)
                            }
                        }
                    },
                    jsonObject = true
                )
            }
        } catch (e: TimeoutCancellationException) {
            throw TranslationFailure(TranslationDiagnostic(
                "STREAM_TIMEOUT", "AI 流式翻译超过 ${STREAM_TIMEOUT_MS / 1000} 秒仍未完成",
                "检查 AI 服务和网络；已改用 Google/Bing 兜底。"
            ))
        } ?: throw TranslationFailure(TranslationDiagnostic(
            "STREAM_FAILED", "AI 流式翻译请求失败",
            "确认当前接口支持流式输出与 JSON 模式，或检查网络、代理和鉴权配置。"
        ))
        markHealthy()
        Log.d(TAG, "Parsing streamed JSON response: ${result.content}")
        return AiTranslationResponseParser.parseValidated(result.content, requestItems)
    }

    suspend fun requestBing(
        options: AiTranslationOptions,
        texts: List<String>,
        song: Song? = null,
        translate: suspend (String?, String, List<String>) -> List<String> = { language, baseUrl, lines ->
            Log.i(TAG, "Bing request via SystemUI direct connection")
            BingLyricsTranslator.translate(lines, language, baseUrl)
        }
    ): List<TranslationItem> {
        val indexed = AiTranslationPolicy.requestItems(texts, options, song)
        if (indexed.isEmpty()) return emptyList()
        val rows = translate(options.targetLanguage, options.bingBaseUrl, indexed.map { it.text })
        if (rows.size != indexed.size || rows.any { it.isBlank() })
            throw TranslationFailure(TranslationDiagnostic("BING_ALIGNMENT", "Bing 译文与原文行数不一致", "稍后重试或检查 Bing 接口。"))
        return acceptTranslatedLines("Bing", indexed, rows, "BING_UNCHANGED",
            "Bing 返回的内容全部与原文相同，未产生有效翻译",
            "检查目标语言，稍后重试；个别未变化的行会保留原文。")
    }

    /**
     * 兜底引擎逐行验收：保留真正变化的译文，未变化的行留在原文，
     * 只有整批都未变化时才判定失败。
     */
    private fun acceptTranslatedLines(
        engine: String,
        indexed: List<TranslationRequestItem>,
        rows: List<String>,
        unchangedCode: String,
        unchangedMessage: String,
        unchangedSuggestion: String
    ): List<TranslationItem> {
        val accepted = ArrayList<TranslationItem>(indexed.size)
        val unchanged = ArrayList<Int>()
        indexed.forEachIndexed { i, item ->
            val row = rows[i].trim()
            if (isUsableLyricTranslation(item.text, row)) {
                accepted += TranslationItem(item.index, row)
            } else {
                unchanged += item.index
                Log.w(TAG, "$engine left line unchanged at index ${item.index}: \"${item.text}\" -> \"$row\"")
            }
        }
        if (accepted.isEmpty()) throw TranslationFailure(
            TranslationDiagnostic(unchangedCode, unchangedMessage, unchangedSuggestion))
        if (unchanged.isNotEmpty()) {
            Log.w(TAG, "$engine kept ${accepted.size}/${indexed.size} translated lines; unchanged indices: $unchanged")
        } else {
            Log.i(TAG, "$engine returned ${accepted.size} translated lines")
        }
        return accepted
    }
}

/**
 * 从流式 JSON 中提取已经闭合的译文对象，用于逐行展示。
 * 只接受请求索引内、与原文不同的完整 `{ "index": n, "tran": "..." }`。
 */
private class StreamingTranslationParser(
    private val allowed: Set<Int>,
    private val sources: Map<Int, String>
) {
    private val buffer = StringBuilder()
    private val starts = ArrayDeque<Int>()
    private val items = LinkedHashMap<Int, TranslationItem>()
    private var scanned = 0
    private var inString = false
    private var escaped = false

    /** @return 新增了译文时返回当前累计结果，否则返回 null。 */
    fun append(delta: String): List<TranslationItem>? {
        buffer.append(delta)
        var added = false
        var i = scanned
        while (i < buffer.length) {
            val c = buffer[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> starts.addLast(i)
                    '}' -> {
                        val start = starts.removeLastOrNull()
                        if (start != null) {
                            val item = parse(buffer.substring(start, i + 1))
                            val translation = item?.translation?.trim().orEmpty()
                            if (item != null && item.index in allowed && translation.isNotBlank() &&
                                isUsableLyricTranslation(sources[item.index], translation) &&
                                item.index !in items
                            ) {
                                items[item.index] = item.copy(translation = translation)
                                added = true
                            }
                        }
                    }
                }
            }
            i++
        }
        scanned = buffer.length
        return if (added) items.values.sortedBy { it.index }.toList() else null
    }

    private fun parse(text: String): TranslationItem? = runCatching {
        val obj = json.parseToJsonElement(text).jsonObject
        val index = obj["index"]?.jsonPrimitive?.intOrNull ?: return@runCatching null
        val translation = obj["tran"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
        TranslationItem(index, translation)
    }.getOrNull()
}
