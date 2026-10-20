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
import java.util.concurrent.atomic.AtomicInteger

/** Bounded scheduler: deduplicates active callers and cancels work with no remaining waiters. */
internal class AiTranslationScheduler(
    private val cache: AiTranslationCache,
    private val generation: AtomicInteger,
    private val maxRunning: Int,
    private val maxPending: Int,
    private val request: suspend (AiConfig, AiTranslationOptions, Song, List<String>) -> List<TranslationItem>? =
        { config, options, song, lines -> AiTranslationRequester.request(config, options, song, lines) },
    private val engine: String = "ai"
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
        originalLines: List<String>
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
        return try {
            job.deferred.await()
        } finally {
            synchronized(lock) {
                job.waiters--
                if (job.waiters == 0 && !job.deferred.isCompleted) cancelLocked(job)
            }
        }
    }

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
            val results = request(job.configs, job.options, job.song, job.originalLines)
            currentCoroutineContext().ensureActive()
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
        var state: TranslationJobState = TranslationJobState.PENDING,
        var waiters: Int = 0,
        var worker: Job? = null
    )

    private enum class TranslationJobState { PENDING, RUNNING, CANCELLED }
}
