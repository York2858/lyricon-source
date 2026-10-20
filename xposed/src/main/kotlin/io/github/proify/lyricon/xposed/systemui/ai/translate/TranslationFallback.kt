package io.github.proify.lyricon.xposed.systemui.ai.translate

import io.github.proify.lyricon.lyric.ai.translate.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope

internal data class FallbackResult(val items: List<TranslationItem>?, val aiError: TranslationDiagnostic? = null,
    val googleError: TranslationDiagnostic? = null, val usedGoogle: Boolean = false)

/** Start both engines together, publish Google early, and keep AI as the final preference. */
internal object TranslationFallback {
    private data class Attempt(val items: List<TranslationItem>? = null, val error: TranslationDiagnostic? = null)

    suspend fun run(enabled: Boolean, ai: suspend () -> List<TranslationItem>?,
        google: suspend () -> List<TranslationItem>?,
        onGoogleReady: (List<TranslationItem>) -> Unit = {}): FallbackResult = coroutineScope {
        val aiTask = async { attempt("AI", ai) }
        val googleTask = if (enabled) async {
            attempt("Google", google).also { result -> result.items?.let(onGoogleReady) }
        } else null

        val aiResult = aiTask.await()
        if (aiResult.items != null) {
            // Finish cancellation before returning, so a late Google callback cannot replace AI.
            googleTask?.cancelAndJoin()
            return@coroutineScope FallbackResult(aiResult.items)
        }
        val googleResult = googleTask?.await()
        FallbackResult(googleResult?.items, aiResult.error, googleResult?.error,
            usedGoogle = googleResult?.items != null)
    }

    private suspend fun attempt(engine: String, request: suspend () -> List<TranslationItem>?): Attempt = try {
        val items = request()?.takeIf { it.isNotEmpty() }
        if (items != null) Attempt(items) else Attempt(error = TranslationDiagnostic(
            if (engine == "Google") "GOOGLE_EMPTY" else "EMPTY_RESPONSE",
            "$engine 没有返回可用译文", "检查接口、模型输出和目标语言，稍后重试。"))
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { Attempt(error = TranslationErrors.from(e)) }
}
