/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.lyric.ai.core

import android.util.Log
import com.openai.client.OpenAIClient
import com.openai.client.OpenAIClientImpl
import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okio.BufferedSink
import java.io.IOException
import java.util.concurrent.TimeUnit
import com.openai.core.http.Headers
import com.openai.core.Timeout
import com.openai.core.ClientOptions
import com.openai.core.http.HttpClient
import com.openai.core.http.HttpRequest
import com.openai.core.http.HttpResponse
import com.openai.core.RequestOptions
import com.openai.core.JsonValue
import com.openai.models.ChatModel
import com.openai.models.ResponseFormatJsonObject
import com.openai.models.chat.completions.ChatCompletionChunk
import com.openai.models.chat.completions.ChatCompletionCreateParams
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 流式请求收尾结果：思考过程（reasoning_content / reasoning）+ 正文内容。 */
data class AiChatStreamResult(
    val reasoning: String,
    val content: String
)

/**
 * 统一的 OpenAI 兼容 Chat Completions 客户端（官方 openai-java SDK，底层 OkHttp）。
 *
 * App 与 SystemUI 进程共用的唯一网络实现：
 * - 支持自定义 baseUrl（DeepSeek 等 OpenAI 兼容服务）与流式（SSE）返回；
 * - 按 `baseUrl|apiKey` 缓存 [OpenAIClient] 实例（有界 LRU），复用底层连接池，
 *   换配置后旧客户端会被 LRU 淘汰释放；
 * - 解析 DeepSeek/Qwen 等兼容服务的思考过程（reasoning_content / reasoning）；
 * - [CancellationException] 一律向上抛出，不吞掉协程取消。
 */
object AiChatClient {
    private const val TAG = "LyriconAiChatClient"
    private const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
    private const val MAX_CLIENTS = 8

    private class ClientEntry(val client: OpenAIClient, val transport: OkHttpClient) {
        var users = 0
        var retired = false
    }
    private val clients = LinkedHashMap<String, ClientEntry>(MAX_CLIENTS, 0.75f, true)

    /**
     * 发起一次非流式 Chat Completions 请求。
     *
     * @param configs 统一 AI 配置
     * @param systemPrompt system 提示词
     * @param userPrompt user 提示词
     * @param jsonObject 是否要求 JSON Object 响应（发送 response_format）
     * @return 完整响应文本；请求失败或响应为空时返回 null
     */
    suspend fun complete(
        configs: AiConfig,
        systemPrompt: String,
        userPrompt: String,
        jsonObject: Boolean = false
    ): String? = withContext(Dispatchers.IO) {
        val apiKey = configs.apiKey ?: return@withContext null
        val baseUrl = resolveBaseUrl(configs)
        val entry = clientFor(baseUrl, apiKey)
        val client = entry.client

        val transport = CancellableTransport(entry.transport)
        try {
            val requestClient = client.withOptions { it.httpClient(transport) }
            val builder = paramsBuilder(configs, systemPrompt, userPrompt)
            if (jsonObject) {
                builder.responseFormat(ResponseFormatJsonObject.builder().build())
            }
            Log.d(TAG, "Connecting to OpenAI compatible API: $baseUrl (stream=false)")

            val response = suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { transport.cancel() }
                val future = requestClient.async().chat().completions().create(builder.build())
                future.whenComplete { result, failure ->
                    if (failure != null) continuation.resumeWithException(failure)
                    else continuation.resume(result)
                }
            }
            response.choices().firstOrNull()?.message()?.content()?.orElse(null)
                ?.trim()?.takeIf { it.isNotBlank() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "OpenAI compatible request failed: ${e.message}", e)
            null
        } finally {
            transport.cancel()
            releaseClient(entry)
        }
    }

    /**
     * 发起一次 SSE 流式 Chat Completions 请求。
     *
     * 思考过程增量回调 [onReasoning]，正文增量回调 [onContent]；
     * 调用方注意回调可能来自 IO 线程。
     *
     * @return 完整思考与正文；请求失败或正文为空时返回 null
     */
    suspend fun stream(
        configs: AiConfig,
        systemPrompt: String,
        userPrompt: String,
        onReasoning: (String) -> Unit = {},
        onContent: (String) -> Unit = {}
    ): AiChatStreamResult? = withContext(Dispatchers.IO) {
        val apiKey = configs.apiKey ?: return@withContext null
        val baseUrl = resolveBaseUrl(configs)
        val entry = clientFor(baseUrl, apiKey)
        val client = entry.client

        val transport = CancellableTransport(entry.transport)
        val cancellationWatcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { transport.cancel() }
        }
        try {
            val params = paramsBuilder(configs, systemPrompt, userPrompt).build()
            Log.d(TAG, "Connecting to OpenAI compatible API: $baseUrl (stream=true)")

            val reasoning = StringBuilder()
            val content = StringBuilder()
            client.withOptions { it.httpClient(transport) }.chat().completions().createStreaming(params).use { respStream ->
                respStream.stream().forEach { chunk ->
                    val delta = chunk.choices().firstOrNull()
                        ?.let { runCatching { it.delta() }.getOrNull() } ?: return@forEach
                    // 注意：增量必须原样透传（空格/换行/纯标点都是合法内容），
                    // 不能以 isNotBlank 过滤，否则会吞掉空白 token 造成文字粘连。
                    delta.content().ifPresent {
                        content.append(it)
                        onContent(it)
                    }
                    reasoningDelta(delta)?.let {
                        reasoning.append(it)
                        onReasoning(it)
                    }
                }
            }

            val finalContent = content.toString().trim()
            if (finalContent.isBlank()) {
                Log.e(TAG, "Empty content in AI stream response.")
                null
            } else {
                AiChatStreamResult(reasoning.toString().trim(), finalContent)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "AI stream failed: ${e.message}", e)
            null
        } finally {
            cancellationWatcher.cancel()
            transport.cancel()
            releaseClient(entry)
        }
    }

    private fun resolveBaseUrl(configs: AiConfig): String {
        return configs.baseUrl?.trim()?.removeSuffix("/")?.takeIf { it.isNotBlank() }
            ?: DEFAULT_BASE_URL
    }

    private fun paramsBuilder(
        configs: AiConfig,
        systemPrompt: String,
        userPrompt: String
    ): ChatCompletionCreateParams.Builder {
        return ChatCompletionCreateParams.builder()
            .model(ChatModel.of(configs.model.orEmpty()))
            .addSystemMessage(systemPrompt)
            .addUserMessage(userPrompt)
            .temperature(configs.temperature.toDouble())
            .topP(configs.topP.toDouble())
            .presencePenalty(configs.presencePenalty.toDouble())
            .frequencyPenalty(configs.frequencyPenalty.toDouble())
    }

    /** DeepSeek/Qwen 等兼容服务的思考过程字段：reasoning_content / reasoning。 */
    private fun reasoningDelta(delta: ChatCompletionChunk.Choice.Delta): String? {
        val value = delta._additionalProperties()["reasoning_content"]
            ?: delta._additionalProperties()["reasoning"]
            ?: return null
        return value.accept(object : JsonValue.Visitor<String?> {
            override fun visitString(value: String): String? = value
            override fun visitNull(): String? = null
            override fun visitDefault(): String? = null
        })
    }

    private fun clientFor(baseUrl: String, apiKey: String): ClientEntry = synchronized(clients) {
        val key = "$baseUrl|$apiKey"
        val entry = clients[key] ?: run {
            val transport = OkHttpClient.Builder().build()
            ClientEntry(OpenAIClientImpl(ClientOptions.builder()
                .httpClient(CancellableTransport(transport, closeDelegate = true)).apiKey(apiKey).baseUrl(baseUrl).build()), transport)
                .also { clients[key] = it }
        }
        entry.users++
        while (clients.size > MAX_CLIENTS) {
            val iterator = clients.entries.iterator()
            val removed = iterator.next().value
            iterator.remove()
            removed.retired = true
            if (removed.users == 0) removed.client.close()
        }
        entry
    }

    private fun releaseClient(entry: ClientEntry) = synchronized(clients) {
        entry.users--
        if (entry.retired && entry.users == 0) entry.client.close()
    }

    /** Keep the OkHttp Call alive through body parsing, so cancellation also aborts stalled bodies. */
    private class CancellableTransport(
        private val delegate: OkHttpClient,
        private val closeDelegate: Boolean = false
    ) : HttpClient {
        private var cancelled = false
        private val calls = mutableSetOf<Call>()

        override fun execute(request: HttpRequest, requestOptions: RequestOptions): HttpResponse =
            executeAsync(request, requestOptions).get()

        override fun executeAsync(request: HttpRequest, requestOptions: RequestOptions): CompletableFuture<HttpResponse> {
            val result = CompletableFuture<HttpResponse>()
            val timeout = requestOptions.timeout ?: Timeout.default()
            val http = delegate.newBuilder()
                .connectTimeout(timeout.connect().toMillis(), TimeUnit.MILLISECONDS)
                .readTimeout(timeout.read().toMillis(), TimeUnit.MILLISECONDS)
                .writeTimeout(timeout.write().toMillis(), TimeUnit.MILLISECONDS)
                .callTimeout(timeout.request().toMillis(), TimeUnit.MILLISECONDS).build()
            val body = request.body?.let { source ->
                object : RequestBody() {
                    override fun contentType() = source.contentType()?.toMediaTypeOrNull()
                    override fun contentLength() = source.contentLength()
                    override fun writeTo(sink: BufferedSink) = source.writeTo(sink.outputStream())
                }
            }
            val builder = Request.Builder().url(request.url()).method(request.method.name, body)
            request.headers.names().forEach { name ->
                request.headers.values(name).forEach { builder.addHeader(name, it) }
            }
            val call = http.newCall(builder.build())
            synchronized(this) {
                if (cancelled) return result.also { it.cancel(true) }
                calls.add(call)
            }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    synchronized(this@CancellableTransport) { calls.remove(call) }
                    request.body?.close()
                    if (call.isCanceled()) result.cancel(true) else result.completeExceptionally(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    request.body?.close()
                    if (call.isCanceled()) {
                        response.close()
                        synchronized(this@CancellableTransport) { calls.remove(call) }
                        result.cancel(true)
                        return
                    }
                    val wrapped = object : HttpResponse {
                        override fun statusCode() = response.code
                        override fun headers() = Headers.builder().putAll(response.headers.toMultimap()).build()
                        override fun body() = response.body!!.byteStream()
                        override fun close() {
                            response.close()
                            synchronized(this@CancellableTransport) { calls.remove(call) }
                        }
                    }
                    if (!result.complete(wrapped)) wrapped.close()
                }
            })
            result.whenComplete { _, _ -> if (result.isCancelled) call.cancel() }
            return result
        }

        fun cancel() {
            val snapshot = synchronized(this) {
                cancelled = true
                calls.toList().also { calls.clear() }
            }
            snapshot.forEach { it.cancel() }
        }

        override fun close() {
            cancel()
            if (closeDelegate) {
                delegate.dispatcher.executorService.shutdown()
                delegate.connectionPool.evictAll()
                delegate.cache?.close()
            }
        }
    }
}
