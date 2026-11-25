/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.xposed.systemui.ai.translate

import android.util.Log
import io.github.proify.lyricon.lyric.ai.core.AiConfig
import io.github.proify.lyricon.lyric.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicInteger

/** Bounded scheduler: deduplicates active callers and cancels work with no remaining waiters. */
internal class AiTranslationScheduler(
    private val cache: AiTranslationCache,
    private val generation: AtomicInteger,
    private val maxRunning: Int,
    private val maxPending: Int,
    private val request: suspend (AiConfig, AiTranslationOptions, Song, List<String>) -> List<TranslationItem>? =
        { config, options, song, lines -> AiTranslationRequester.request(config, options, song, lines) },
    private val engine: String = "ai",
    /** 流式请求：第一段正文回调视为健康信号（思考不算），闭合的译文行通过 onPartial 增量发布。 */
    private val streamingRequest: (suspend (
        AiConfig, AiTranslationOptions, Song, List<String>,
        onHealth: () -> Unit,
        onPartial: (List<TranslationItem>) -> Unit
    ) -> List<TranslationItem>?)? = null
) {
    private companion object {
        const val TAG = "LyriconAiTranslator"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lock = Any()
    private val jobs = ConcurrentHashMap<String, TranslationJob>()
    private val pending = ArrayDeque<TranslationJob>()
    private var running = 0

    /** Each caller holds a lease. The final cancelled waiter cancels the network request. */
    suspend fun awaitTranslation(
        key: String,
        configs: AiConfig,
        options: AiTranslationOptions,
        song: Song,
        originalLines: List<String>,
        onHealth: (() -> Unit)? = null,
        onPartial: ((List<TranslationItem>) -> Unit)? = null
    ): List<TranslationItem>? {
        val job = synchronized(lock) {
            jobs[key]?.also { it.waiters++ } ?: TranslationJob(
                key = key,
                songName = song.name.orEmpty(),
                configs = configs,
                options = options,
                song = song,
                originalLines = originalLines,
                generation = generation.get(),
                waiters = 1
            ).also {
                jobs[key] = it
                pending.addLast(it)
                trimPendingLocked()
                dispatchNextLocked()
            }
        }
        val healthWatcher = onHealth?.let { callback ->
            scope.launch {
                try {
                    job.health.await()
                    callback()
                } catch (_: CancellationException) {
                    // The caller finished or the job was cancelled.
                }
            }
        }
        onPartial?.let { job.partialListeners.add(it) }
        return try {
            job.deferred.await()
        } finally {
            healthWatcher?.cancel()
            onPartial?.let { job.partialListeners.remove(it) }
            synchronized(lock) {
                job.waiters--
                if (job.waiters == 0 && !job.deferred.isCompleted) cancelLocked(job)
            }
        }
    }

    fun cancel(key: String) { synchronized(lock) { jobs[key]?.let(::cancelLocked) } }

    fun cancelAll() {
        synchronized(lock) {
            pending.toList().forEach(::cancelLocked)
            jobs.values.toList().forEach(::cancelLocked)
        }
    }

    private fun cancelLocked(job: TranslationJob) {
        job.state = TranslationJobState.CANCELLED
        jobs.remove(job.key, job)
        pending.remove(job)
        job.worker?.cancel()
        job.deferred.cancel()
    }

    private fun trimPendingLocked() {
        while (pending.size > maxPending) cancelLocked(pending.first())
    }

    private fun dispatchNextLocked() {
        while (running < maxRunning && pending.isNotEmpty()) {
            val job = pending.removeLast()
            if (job.state != TranslationJobState.PENDING) continue
            job.state = TranslationJobState.RUNNING
            running++
            val worker = scope.launch(start = CoroutineStart.LAZY) { runJob(job) }
            job.worker = worker
            // Completion runs even if the worker is cancelled before its body starts.
            worker.invokeOnCompletion {
                synchronized(lock) {
                    running--
                    jobs.remove(job.key, job)
                    dispatchNextLocked()
                }
            }
            worker.start()
        }
    }

    private suspend fun runJob(job: TranslationJob) {
        try {
            val stream = streamingRequest
            val results = if (stream != null) {
                stream(
                    job.configs, job.options, job.song, job.originalLines,
                    { job.health.complete(Unit) },
                    { items -> job.partialListeners.forEach { runCatching { it(items) } } }
                )
            } else {
                request(job.configs, job.options, job.song, job.originalLines)
            }
            currentCoroutineContext().ensureActive()
            if (!results.isNullOrEmpty()) job.health.complete(Unit)
            val current = job.generation == generation.get()
            if (!results.isNullOrEmpty() && current) {
                cache.saveToDb(job.key, results, job.song, job.originalLines,
                    job.configs, job.options, job.generation, engine)
            }
            job.deferred.complete(if (current) results else null)
        } catch (e: CancellationException) {
            job.deferred.cancel(e)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "AI translation failed for [${job.songName}]", e)
            job.deferred.completeExceptionally(e)
        }
    }

    private data class TranslationJob(
        val key: String,
        val songName: String,
        val configs: AiConfig,
        val options: AiTranslationOptions,
        val song: Song,
        val originalLines: List<String>,
        val generation: Int,
        val deferred: CompletableDeferred<List<TranslationItem>?> = CompletableDeferred(),
        val health: CompletableDeferred<Unit> = CompletableDeferred(),
        val partialListeners: MutableSet<(List<TranslationItem>) -> Unit> = CopyOnWriteArraySet(),
        var state: TranslationJobState = TranslationJobState.PENDING,
        var waiters: Int = 0,
        var worker: Job? = null
    )

    private enum class TranslationJobState { PENDING, RUNNING, CANCELLED }
}
