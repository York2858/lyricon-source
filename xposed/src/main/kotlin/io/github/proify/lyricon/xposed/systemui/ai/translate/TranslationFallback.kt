package io.github.proify.lyricon.xposed.systemui.ai.translate

import android.util.Log
import io.github.proify.lyricon.lyric.ai.translate.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

internal data class FallbackResult(val items: List<TranslationItem>?, val aiError: TranslationDiagnostic? = null,
    val googleError: TranslationDiagnostic? = null, val usedGoogle: Boolean = false)

internal data class HealthGatedResult(val items: List<TranslationItem>?, val aiError: TranslationDiagnostic? = null,
    val fallbackError: TranslationDiagnostic? = null, val usedFallback: Boolean = false, val aiHealthy: Boolean = false)

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

    /**
     * 先只看 AI：流式请求在 [healthTimeoutMs] 内给出第一段**正文**（只有思考不算）即视为正常，
     * 全程不启动兜底；否则立刻并行启动 Google/Bing 并展示结果，但**不取消 AI**：
     * AI 之后若成功仍以 AI 为准，只有 AI 失败或超时才保留兜底结果。
     */
    suspend fun runHealthGated(
        health: CompletableDeferred<Unit>,
        ai: suspend () -> List<TranslationItem>?,
        fallbacks: List<Pair<String, suspend () -> List<TranslationItem>?>>,
        healthTimeoutMs: Long,
        onFallbackReady: (List<TranslationItem>) -> Unit = {}
    ): HealthGatedResult = coroutineScope {
        val aiTask = async { attempt("AI", ai) }
        val healthy = withTimeoutOrNull(healthTimeoutMs) {
            select<Boolean> {
                health.onAwait { true }
                aiTask.onAwait { false }
            }
        } ?: false

        if (healthy) {
            val aiResult = aiTask.await()
            if (aiResult.items != null) {
                return@coroutineScope HealthGatedResult(aiResult.items, aiHealthy = true)
            }
            // The stream started but the final result was unusable; fall back.
            val fallback = attempt("Fallback") { firstSuccessfulFallback(fallbacks, onFallbackReady) }
            return@coroutineScope HealthGatedResult(
                fallback.items, aiResult.error, fallback.error,
                usedFallback = fallback.items != null, aiHealthy = true
            )
        }

        // 正文迟迟不来（可能一直在思考）：先展示兜底译文，AI 流继续保持。
        Log.i(
            "LyriconAiTranslator",
            "AI produced no answer body within ${healthTimeoutMs}ms; starting parallel fallback"
        )
        val fallbackTask = async {
            attempt("Fallback") { firstSuccessfulFallback(fallbacks, onFallbackReady) }
        }
        // Bounded by the stream request's own timeout; AI quality wins when it eventually arrives.
        val aiResult = aiTask.await()
        if (aiResult.items != null) {
            // Finish cancellation before returning, so a late fallback callback cannot override AI.
            fallbackTask.cancelAndJoin()
            Log.i("LyriconAiTranslator", "AI recovered after slow reasoning; preferring AI result")
            return@coroutineScope HealthGatedResult(aiResult.items)
        }
        val fallback = fallbackTask.await()
        HealthGatedResult(
            fallback.items,
            aiResult.error ?: TranslationDiagnostic(
                "AI_TIMEOUT", "AI 在 ${healthTimeoutMs / 1000} 秒内没有返回正文",
                "检查网络、代理或 AI 服务；已改用 Google/Bing 兜底。"
            ),
            fallback.error,
            usedFallback = fallback.items != null
        )
    }

    private suspend fun attempt(engine: String, request: suspend () -> List<TranslationItem>?): Attempt = try {
        val items = request()?.takeIf { it.isNotEmpty() }
        if (items != null) Attempt(items) else Attempt(error = TranslationDiagnostic(
            if (engine == "Google") "GOOGLE_EMPTY" else "EMPTY_RESPONSE",
            "$engine 没有返回可用译文", "检查接口、模型输出和目标语言，稍后重试。"))
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { Attempt(error = TranslationErrors.from(e)) }
}

/**
 * Run every no-key fallback engine together and return the first usable result.
 * Every engine is cancelled as soon as one wins; a combined diagnostic is thrown
 * only when all of them fail.
 */
internal suspend fun firstSuccessfulFallback(
    engines: List<Pair<String, suspend () -> List<TranslationItem>?>>,
    onReady: (List<TranslationItem>) -> Unit = {}
): List<TranslationItem>? = coroutineScope {
    if (engines.isEmpty()) return@coroutineScope null
    val success = CompletableDeferred<List<TranslationItem>?>()
    val errors = Collections.synchronizedList(mutableListOf<Pair<String, Throwable>>())
    val remaining = AtomicInteger(engines.size)
    val jobs = engines.map { (name, request) ->
        launch(Dispatchers.IO) {
            try {
                val items = request()
                if (!items.isNullOrEmpty()) {
                    Log.i("LyriconAiTranslator", "Fallback engine $name succeeded (${items.size} lines)")
                    runCatching { onReady(items) }
                    success.complete(items)
                } else {
                    Log.w("LyriconAiTranslator", "Fallback engine $name returned no lines")
                    errors += name to TranslationFailure(
                        TranslationDiagnostic("${name.uppercase()}_EMPTY", "$name 没有返回可用译文", "稍后重试。")
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w("LyriconAiTranslator", "Fallback engine $name failed: ${e.message}")
                errors += name to e
            } finally {
                if (remaining.decrementAndGet() == 0 && !success.isCompleted) success.complete(null)
            }
        }
    }
    val items = try {
        success.await()
    } finally {
        jobs.forEach { it.cancel() }
    }
    if (items == null) {
        val detail = errors.joinToString("\n") { (name, error) ->
            val diagnostic = (error as? TranslationFailure)?.diagnostic ?: TranslationErrors.from(error)
            "$name：${diagnostic.reason}"
        }
        throw TranslationFailure(
            TranslationDiagnostic("FALLBACK_FAILED", detail, "检查网络，或在 AI 实验室调整无密钥翻译接口。")
        )
    }
    items
}
