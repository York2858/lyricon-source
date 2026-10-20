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
}
