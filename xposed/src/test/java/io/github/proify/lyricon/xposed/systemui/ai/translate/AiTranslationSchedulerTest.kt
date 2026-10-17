package io.github.proify.lyricon.xposed.systemui.ai.translate

import io.github.proify.lyricon.lyric.ai.core.AiConfig
import io.github.proify.lyricon.lyric.model.Song
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class AiTranslationSchedulerTest {
    private fun scheduler(scope: CoroutineScope,
        request: suspend (AiConfig, AiTranslationOptions, Song, List<String>) -> List<TranslationItem>?
    ): AiTranslationScheduler {
        val generation = AtomicInteger()
        return AiTranslationScheduler(AiTranslationCache(4, generation, scope), generation, 2, 2, request)
    }
    private suspend fun AiTranslationScheduler.await(key: String) =
        awaitTranslation(key, AiConfig(), AiTranslationOptions(), Song(name = key), listOf("lyrics"))

    @Test fun lastCancelledWaiterCancelsRunningRequest() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val scheduler = scheduler(this) { _, _, _, _ ->
            started.complete(Unit)
            try { awaitCancellation() } finally { stopped.complete(Unit) }
        }
        val waiter = async { scheduler.await("old") }
        withTimeout(2000) { started.await() }
        waiter.cancelAndJoin()
        withTimeout(2000) { stopped.await() }
    }

    @Test fun sameSongRemainsSharedUntilLastWaiterLeaves() = runBlocking {
        val calls = AtomicInteger()
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val scheduler = scheduler(this) { _, _, _, _ ->
            calls.incrementAndGet()
            started.complete(Unit)
            try { awaitCancellation() } finally { stopped.complete(Unit) }
        }
        val first = async(start = CoroutineStart.UNDISPATCHED) { scheduler.await("same") }
        withTimeout(2000) { started.await() }
        val second = async(start = CoroutineStart.UNDISPATCHED) { scheduler.await("same") }
        first.cancelAndJoin()
        assertEquals(1, calls.get())
        assertFalse(stopped.isCompleted)
        second.cancelAndJoin()
        withTimeout(2000) { stopped.await() }
    }

    @Test fun concurrencyIsBoundedAndClearingCancelsQueuedWork() = runBlocking {
        val active = AtomicInteger()
        val max = AtomicInteger()
        val bothStarted = CompletableDeferred<Unit>()
        val scheduler = scheduler(this) { _, _, _, _ ->
            val count = active.incrementAndGet()
            max.updateAndGet { maxOf(it, count) }
            if (count == 2) bothStarted.complete(Unit)
            try { awaitCancellation() } finally { active.decrementAndGet() }
        }
        val first = async { scheduler.await("first") }
        val second = async { scheduler.await("second") }
        withTimeout(2000) { bothStarted.await() }
        val queued = async(start = CoroutineStart.UNDISPATCHED) { scheduler.await("queued") }
        scheduler.cancelAll()
        listOf(first, second, queued).forEach { it.cancelAndJoin() }
        withTimeout(2000) { while (active.get() != 0) yield() }
        assertEquals(2, max.get())
    }
}
