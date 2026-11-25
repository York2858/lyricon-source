package io.github.proify.lyricon.xposed.systemui.ai.translate

import io.github.proify.lyricon.lyric.ai.translate.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class TranslationFallbackTest {
    private val items = listOf(TranslationItem(1, "AI 译文"))
    private val googleItems = listOf(TranslationItem(1, "Google 译文"))

    @Test fun bothEnginesStartTogetherAndGoogleIsShownBeforeAiReplacesIt() = runBlocking {
        val aiStarted = CompletableDeferred<Unit>()
        val googleStarted = CompletableDeferred<Unit>()
        val releaseAi = CompletableDeferred<Unit>()
        val releaseGoogle = CompletableDeferred<Unit>()
        val preview = CompletableDeferred<List<TranslationItem>>()
        val running = async {
            TranslationFallback.run(true,
                ai = { aiStarted.complete(Unit); releaseAi.await(); items },
                google = { googleStarted.complete(Unit); releaseGoogle.await(); googleItems },
                onGoogleReady = { preview.complete(it) })
        }
        withTimeout(2000) { aiStarted.await(); googleStarted.await() }
        releaseGoogle.complete(Unit)
        assertEquals(googleItems, withTimeout(2000) { preview.await() })
        assertFalse(running.isCompleted)
        releaseAi.complete(Unit)
        val result = withTimeout(2000) { running.await() }
        assertEquals(items, result.items); assertFalse(result.usedGoogle); assertNull(result.aiError)
    }

    @Test fun fastAiCancelsAnUnfinishedGoogleRequestWithoutALatePreview() = runBlocking {
        val googleStarted = CompletableDeferred<Unit>()
        val googleCancelled = CompletableDeferred<Unit>()
        var previews = 0
        val result = withTimeout(2000) {
            TranslationFallback.run(true,
                ai = { googleStarted.await(); items },
                google = { try { googleStarted.complete(Unit); awaitCancellation() } finally { googleCancelled.complete(Unit) } },
                onGoogleReady = { previews++ })
        }
        assertEquals(items, result.items)
        assertTrue(googleCancelled.isCompleted); assertEquals(0, previews)
    }

    @Test fun failedAiKeepsGoogleAndTheExactFailureForTheLibrary() = runBlocking {
        val failure = TranslationErrors.http(401)
        var preview: List<TranslationItem>? = null
        val result = TranslationFallback.run(true, { throw TranslationFailure(failure) }, { googleItems }, { preview = it })
        assertEquals(googleItems, result.items); assertEquals(failure, result.aiError); assertTrue(result.usedGoogle)
        assertEquals(googleItems, preview)
    }

    @Test fun googleFailureDoesNotCancelSuccessfulAi() = runBlocking {
        val googleFailed = CompletableDeferred<Unit>()
        val result = withTimeout(2000) {
            TranslationFallback.run(true,
                ai = { googleFailed.await(); yield(); items },
                google = { googleFailed.complete(Unit); throw TranslationFailure(TranslationErrors.googleHttp(403)) })
        }
        assertEquals(items, result.items); assertFalse(result.usedGoogle)
    }

    @Test fun bothFailuresAreRetainedAndDisabledGoogleDoesNotMakeARequest() = runBlocking {
        val ai = TranslationErrors.http(429)
        val google = TranslationDiagnostic("DNS", "Google 域名解析失败", "检查网络")
        val failed = TranslationFallback.run(true, { throw TranslationFailure(ai) }, { throw TranslationFailure(google) })
        assertNull(failed.items); assertEquals(ai, failed.aiError); assertEquals(google, failed.googleError)
        var calls = 0
        val disabled = TranslationFallback.run(false, { null }, { calls++; googleItems })
        assertNull(disabled.items); assertEquals(0, calls)
    }

    @Test fun changingSongsCancelsBothEnginesWithoutPublishingOldLyrics() = runBlocking {
        val aiStarted = CompletableDeferred<Unit>()
        val googleStarted = CompletableDeferred<Unit>()
        val aiCancelled = CompletableDeferred<Unit>()
        val googleCancelled = CompletableDeferred<Unit>()
        var previews = 0
        val running = launch {
            TranslationFallback.run(true,
                ai = { try { aiStarted.complete(Unit); awaitCancellation() } finally { aiCancelled.complete(Unit) } },
                google = { try { googleStarted.complete(Unit); awaitCancellation() } finally { googleCancelled.complete(Unit) } },
                onGoogleReady = { previews++ })
        }
        withTimeout(2000) { aiStarted.await(); googleStarted.await(); running.cancelAndJoin() }
        assertTrue(aiCancelled.isCompleted); assertTrue(googleCancelled.isCompleted); assertEquals(0, previews)
    }

    @Test fun answerBodyWithinTimeoutNeverStartsFallback() = runBlocking {
        val health = CompletableDeferred<Unit>()
        var fallbackCalls = 0
        val running = async {
            TranslationFallback.runHealthGated(
                health = health,
                ai = { health.await(); items },
                fallbacks = listOf("Bing" to { fallbackCalls++; googleItems }),
                healthTimeoutMs = 5000
            )
        }
        health.complete(Unit)
        val outcome = withTimeout(2000) { running.await() }
        assertEquals(items, outcome.items)
        assertTrue(outcome.aiHealthy)
        assertFalse(outcome.usedFallback)
        assertEquals(0, fallbackCalls)
    }

    @Test fun reasoningWithoutBodyStartsFallbackButLateAiStillWins() = runBlocking {
        val fallbackStarted = CompletableDeferred<Unit>()
        val releaseAi = CompletableDeferred<Unit>()
        var preview: List<TranslationItem>? = null
        val running = async {
            TranslationFallback.runHealthGated(
                health = CompletableDeferred(),
                ai = { releaseAi.await(); items },
                fallbacks = listOf("Bing" to { fallbackStarted.complete(Unit); googleItems }),
                healthTimeoutMs = 50,
                onFallbackReady = { preview = it }
            )
        }
        withTimeout(2000) { fallbackStarted.await() }
        withTimeout(2000) { while (preview == null) delay(10) }
        assertEquals(googleItems, preview)
        releaseAi.complete(Unit)
        val outcome = withTimeout(2000) { running.await() }
        assertEquals(items, outcome.items)
        assertFalse(outcome.usedFallback)
        assertNull(outcome.aiError)
    }

    @Test fun lateAiRecoveryCancelsARunningFallbackBeforeReturning() = runBlocking {
        val fallbackStarted = CompletableDeferred<Unit>()
        val fallbackCancelled = CompletableDeferred<Unit>()
        val releaseAi = CompletableDeferred<Unit>()
        val running = async {
            TranslationFallback.runHealthGated(
                health = CompletableDeferred(),
                ai = { releaseAi.await(); items },
                fallbacks = listOf("Bing" to {
                    fallbackStarted.complete(Unit)
                    try { awaitCancellation() } finally { fallbackCancelled.complete(Unit) }
                }),
                healthTimeoutMs = 50
            )
        }
        withTimeout(2000) { fallbackStarted.await() }
        releaseAi.complete(Unit)
        val outcome = withTimeout(2000) { running.await() }
        assertEquals(items, outcome.items)
        assertTrue(fallbackCancelled.isCompleted)
    }

    @Test fun slowAiFailureKeepsTheFallbackResultAndDiagnostic() = runBlocking {
        val failure = TranslationErrors.http(500)
        val outcome = withTimeout(2000) {
            TranslationFallback.runHealthGated(
                health = CompletableDeferred(),
                ai = { delay(150); throw TranslationFailure(failure) },
                fallbacks = listOf("Bing" to { googleItems }),
                healthTimeoutMs = 20
            )
        }
        assertEquals(googleItems, outcome.items)
        assertEquals(failure, outcome.aiError)
        assertTrue(outcome.usedFallback)
    }

    @Test fun slowAiAndAllFallbacksFailRetainBothErrors() = runBlocking {
        val ai = TranslationErrors.http(500)
        val outcome = withTimeout(2000) {
            TranslationFallback.runHealthGated(
                health = CompletableDeferred(),
                ai = { delay(100); throw TranslationFailure(ai) },
                fallbacks = listOf("Bing" to {
                    throw TranslationFailure(TranslationDiagnostic("BING", "Bing 失败", "稍后重试"))
                }),
                healthTimeoutMs = 20
            )
        }
        assertNull(outcome.items)
        assertEquals(ai, outcome.aiError)
        assertNotNull(outcome.fallbackError)
        assertFalse(outcome.usedFallback)
    }
}
