/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.xposed.systemui.ai.translate

import android.content.Context
import android.util.Log
import io.github.proify.android.extensions.md5
import io.github.proify.lyricon.lyric.ai.translate.*
import io.github.proify.lyricon.lyric.ai.core.AiConfig
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.xposed.systemui.ai.translate.AiTranslator.translateSongSync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.atomic.AtomicInteger

/**
 * SystemUI 进程内的 AI 歌词翻译门面。
 *
 * 职责边界：
 * - [AiTranslationKey] 生成整首歌级缓存 key；
 * - [AiTranslationCache] 内存 + SQLite 双级缓存；
 * - [AiTranslationScheduler] 暴力切歌场景下的 pending/running 调度、同 key 复用与队列淘汰；
 * - [AiTranslationRequester] 筛选行、组请求并解析响应；
 * - [AiTranslationApplicator] 把有效译文写回 Song。
 *
 * 同一首歌的请求由当前等待者共享；最后一个等待者取消时，排队与网络任务一起取消。
 */
object AiTranslator {
    private const val TAG = "LyriconAiTranslator"
    private const val MAX_CACHE_SIZE = 1000
    private const val MAX_RUNNING_TRANSLATIONS = 2
    private const val MAX_PENDING_TRANSLATIONS = 2

    /**
     * AI 流式请求在这个时间内没有返回第一段正文（只有思考也算没有正文），
     * 就先并行启动无密钥兜底并展示；AI 流不取消，之后成功仍以 AI 为准。
     */
    private const val AI_FIRST_CONTENT_TIMEOUT_MS = 10_000L

    private val cacheGeneration = AtomicInteger(0)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val cache = AiTranslationCache(
        maxCacheSize = MAX_CACHE_SIZE,
        generation = cacheGeneration,
        scope = scope
    )
    private var libraryEndpoint: TranslationLibraryEndpoint? = null

    fun libraryMessenger(): android.os.Messenger? = libraryEndpoint?.messenger
    suspend fun syncLyricOffset(song: Song, offset: Int) { cache.setLyricOffset(song, offset) }
    suspend fun getLyricOffset(song: Song): Pair<String, Int> = cache.getLyricOffset(song)
    private val scheduler = AiTranslationScheduler(
        cache = cache,
        generation = cacheGeneration,
        maxRunning = MAX_RUNNING_TRANSLATIONS,
        maxPending = MAX_PENDING_TRANSLATIONS,
        streamingRequest = { config, options, song, lines, onHealth, onPartial ->
            AiTranslationRequester.requestStreaming(config, options, song, lines, onHealth, onPartial)
        }
    )

    private val googleScheduler = AiTranslationScheduler(cache, cacheGeneration, 2, 2,
        request = { _, options, song, texts -> AiTranslationRequester.requestGoogle(options, texts, song) }, engine = "google")

    private val bingScheduler = AiTranslationScheduler(cache, cacheGeneration, 2, 2,
        request = { _, options, song, texts -> AiTranslationRequester.requestBing(options, texts, song) }, engine = "bing")

    suspend fun recordOutcome(song: Song, status: String, reason: TranslationDiagnostic,
        configs: AiConfig? = null, options: AiTranslationOptions = AiTranslationOptions(), resolution: String = "",
        expectedGeneration: Int = cacheGeneration.get()) {
        try { cache.saveOutcome(song, status, reason, configs, options, resolution, expectedGeneration) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { Log.w(TAG, "Unable to save translation outcome: ${e.javaClass.simpleName}") }
    }

    fun init(context: Context) {
        cache.init(context)
        if (libraryEndpoint == null) libraryEndpoint = TranslationLibraryEndpoint(context, cache)
        AiTranslationAppClient.initialize(context)
    }

    suspend fun resolveWaitingForLyrics(song: Song) {
        try { cache.resolveWaitingForLyrics(song) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { Log.w(TAG, "Unable to update lyric arrival: ${e.javaClass.simpleName}") }
    }

    suspend fun translateSongSync(
        song: Song,
        configs: AiConfig,
        options: AiTranslationOptions = AiTranslationOptions(),
        onProgress: (Song) -> Unit = {},
        forceRefresh: Boolean = false
    ): Song {
        if (!configs.isUsable) {
            Log.w(TAG, "Translation skipped: Configs not usable (missing API Key or disabled).")
            return song
        }
        if (song.lyrics.isNullOrEmpty()) return song

        return try {
            translateSong(song, configs, options, onProgress, forceRefresh)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Critical error during translateSongSync: ${e.message}", e)
            song
        }
    }

    /** Kept public for the existing network test. Production flow should go through [translateSongSync]. */
    suspend fun doOpenAiRequest(
        configs: AiConfig,
        song: Song? = null,
        texts: List<String>
    ): List<TranslationItem>? =
        AiTranslationRequester.request(configs, AiTranslationOptions(), song, texts)

    fun clearCache(callback: () -> Unit) {
        Log.i(TAG, "Clearing all translation caches (Memory & DB)...")
        scheduler.cancelAll(); googleScheduler.cancelAll(); bingScheduler.cancelAll()
        cache.clear(callback)
    }

    private suspend fun translateSong(
        song: Song,
        configs: AiConfig,
        options: AiTranslationOptions,
        onProgress: (Song) -> Unit,
        forceRefresh: Boolean
    ): Song {
        val currentLyrics = song.lyrics ?: return song
        val originalLines = currentLyrics.map { it.text?.trim() ?: "" }
        if (AiTranslationPolicy.requestItems(originalLines, options, song).isEmpty()) return song
        val songContentId = AiTranslationKey.calculate(configs, options, song, originalLines)

        if (!forceRefresh) cache.getFromMemory(songContentId)?.let {
            Log.d(TAG, "Memory cache hit for: ${song.name} [$songContentId]")
            cache.enrichMetadata(songContentId, song, originalLines, configs, options)
            return AiTranslationApplicator.apply(song, it)
        }

        if (!forceRefresh) cache.getFromDb(songContentId)?.let {
            Log.d(TAG, "Database cache hit for: ${song.name} [$songContentId]")
            cache.enrichMetadata(songContentId, song, originalLines, configs, options)
            // A deletion between the DB lookup and metadata update must not repopulate memory.
            return AiTranslationApplicator.apply(song, it)
        }

        Log.i(
            TAG,
            "Cache miss. Waiting for AI translation: ${song.name} (${originalLines.size} lines)"
        )
        val generation = cacheGeneration.get()
        val googleConfig = AiConfig(model = "Google Translate", baseUrl = options.googleBaseUrl)
        val googleOptions = options.copy(stylePrompt = null)
        val googleKey = "google:$songContentId:${configs.apiKey.orEmpty().md5()}:" + AiTranslationKey.calculate(googleConfig, googleOptions, song, originalLines)
        val bingConfig = AiConfig(model = "Bing Translate", baseUrl = options.bingBaseUrl)
        val bingOptions = options.copy(stylePrompt = null)
        val bingKey = "bing:$songContentId:" + AiTranslationKey.calculate(bingConfig, bingOptions, song, originalLines)
        if (forceRefresh) {
            scheduler.cancel(songContentId); googleScheduler.cancel(googleKey); bingScheduler.cancel(bingKey)
        }
        val cachedGoogle = if (options.googleFallback && !forceRefresh) (cache.getFromMemory(googleKey) ?: cache.getFromDb(googleKey))?.also {
            cache.enrichMetadata(googleKey, song, originalLines, googleConfig, googleOptions)
        } else null
        val cachedBing = if (options.bingFallback && !forceRefresh) (cache.getFromMemory(bingKey) ?: cache.getFromDb(bingKey))?.also {
            cache.enrichMetadata(bingKey, song, originalLines, bingConfig, bingOptions)
        } else null
        val fallbackEngines: List<Pair<String, suspend () -> List<TranslationItem>?>> = buildList {
            if (options.googleFallback) add("Google" to {
                cachedGoogle ?: googleScheduler.awaitTranslation(googleKey, googleConfig, googleOptions, song, originalLines)
            })
            if (options.bingFallback) add("Bing" to {
                cachedBing ?: bingScheduler.awaitTranslation(bingKey, bingConfig, bingOptions, song, originalLines)
            })
        }
        val health = CompletableDeferred<Unit>()
        val outcome = TranslationFallback.runHealthGated(
            health = health,
            ai = {
                scheduler.awaitTranslation(
                    key = songContentId,
                    configs = configs,
                    options = options,
                    song = song,
                    originalLines = originalLines,
                    onHealth = { health.complete(Unit) },
                    onPartial = { items -> onProgress(AiTranslationApplicator.apply(song, items)) }
                )
            },
            fallbacks = fallbackEngines,
            healthTimeoutMs = AI_FIRST_CONTENT_TIMEOUT_MS,
            onFallbackReady = { onProgress(AiTranslationApplicator.apply(song, it)) }
        )
        outcome.aiError?.let { aiError ->
            val diagnostic = if (outcome.fallbackError == null) aiError else TranslationDiagnostic(
                "AI_AND_FALLBACK", "AI：${aiError.reason}\n无密钥兜底：${outcome.fallbackError.reason}",
                "AI：${aiError.suggestion}\n兜底：${outcome.fallbackError.suggestion}", aiError.httpStatus)
            recordOutcome(song, "error", diagnostic, configs, options,
                when {
                    outcome.usedFallback -> "无密钥兜底翻译成功，已显示补译结果"
                    outcome.fallbackError != null -> "AI 与无密钥兜底均失败，保留原歌词及已有译文"
                    else -> "AI 未在限定时间内返回，无密钥兜底不可用"
                }, generation)
        }
        return outcome.items?.let { AiTranslationApplicator.apply(song, it) } ?: song
    }
}
