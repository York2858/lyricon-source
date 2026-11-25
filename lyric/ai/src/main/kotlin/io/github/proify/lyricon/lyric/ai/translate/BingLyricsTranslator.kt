/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.lyric.ai.translate

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Bing 国内站（cn.bing.com）的网页翻译接口，无需 API Key，也不要求访问外网。
 *
 * 页面中的 IG / IID / token 与 Cookie 绑定，因此先取一次会话，再按批提交歌词。
 * 与 Google 兜底一样，批量结果行数不一致时退回逐行翻译，保证译文与原文一一对应。
 */
object BingLyricsTranslator {
    const val DEFAULT_URL = "https://cn.bing.com"

    private const val MAX_BATCH_CHARS = 1_500
    private const val MAX_PARALLEL_LINES = 4
    private const val SESSION_TTL_MS = 10 * 60_000L
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/134.0.0.0 Mobile Safari/537.36"

    private val IG_REGEX = Regex("""IG:"([^"]+)"""")
    private val IID_REGEX = Regex("""data-iid="(translator\.[^"]+)"""")
    private val HELPER_REGEX =
        Regex("""params_AbusePreventionHelper\s*=\s*\[\s*(\d+)\s*,\s*"([^"]+)"\s*,\s*\d+\s*]""")
    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS).build()

    private data class Session(val ig: String, val iid: String, val token: String, val key: String, val cookie: String)
    private data class CachedSession(val base: String, val session: Session, val loadedAt: Long)

    @Volatile private var cachedSession: CachedSession? = null

    suspend fun translate(texts: List<String>, language: String?, baseUrl: String = DEFAULT_URL): List<String> {
        val base = baseUrl.trim().trimEnd('/').ifBlank { DEFAULT_URL }
        val target = bingTargetLanguage(language)
        val output = arrayOfNulls<String>(texts.size)
        val pending = ArrayList<Pair<Int, String>>(texts.size)
        texts.forEachIndexed { index, text ->
            if (text.isBlank()) output[index] = text else pending += index to text
        }
        if (pending.isEmpty()) return texts

        batches(pending).forEach { batch ->
            val raw = requestText(base, batch.joinToString("\n") { it.second }, target)
            val lines = raw.replace("\r\n", "\n").split('\n')
            if (lines.size == batch.size) {
                batch.forEachIndexed { index, item -> output[item.first] = lines[index] }
            } else {
                // Bing occasionally merges or splits adjacent lines; retry each one.
                val semaphore = Semaphore(MAX_PARALLEL_LINES)
                coroutineScope {
                    batch.map { item ->
                        async(Dispatchers.IO) {
                            semaphore.withPermit {
                                item.first to requestText(base, item.second, target)
                            }
                        }
                    }.awaitAll().forEach { (index, translated) ->
                        output[index] = translated.replace("\r\n", "\n").replace('\n', ' ').trim()
                    }
                }
            }
        }
        if (output.any { it.isNullOrBlank() }) {
            throw TranslationFailure(
                TranslationDiagnostic("BING_ALIGNMENT", "Bing 译文行数不完整", "稍后重试，或改用其他翻译接口。")
            )
        }
        return output.map { it!! }
    }

    fun bingTargetLanguage(raw: String?): String {
        val normalized = GoogleLyricsTranslator.targetLanguage(raw)
        return when (normalized.lowercase()) {
            "zh", "zh-cn", "zh-hans" -> "zh-Hans"
            "zh-tw", "zh-hant" -> "zh-Hant"
            else -> normalized
        }
    }

    private fun batches(items: List<Pair<Int, String>>): List<List<Pair<Int, String>>> {
        val result = mutableListOf<List<Pair<Int, String>>>()
        var current = mutableListOf<Pair<Int, String>>()
        var size = 0
        items.forEach { item ->
            val added = item.second.length + if (current.isEmpty()) 0 else 1
            if (current.isNotEmpty() && size + added > MAX_BATCH_CHARS) {
                result += current
                current = mutableListOf()
                size = 0
            }
            current += item
            size += item.second.length + if (current.size > 1) 1 else 0
        }
        if (current.isNotEmpty()) result += current
        return result
    }

    private suspend fun requestText(base: String, text: String, target: String): String {
        val session = currentSession(base)
        return try {
            postText(base, session, text, target)
        } catch (e: SessionExpired) {
            postText(base, loadSession(base), text, target)
        }
    }

    private suspend fun currentSession(base: String): Session {
        val cached = cachedSession
        if (cached != null && cached.base == base &&
            System.currentTimeMillis() - cached.loadedAt < SESSION_TTL_MS
        ) return cached.session
        return loadSession(base)
    }

    private suspend fun loadSession(base: String): Session {
        val (body, cookie) = executeRequestWithHeaders(
            Request.Builder()
                .url("$base/translator")
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
        )
        val ig = IG_REGEX.find(body)?.groupValues?.get(1)
        val iid = IID_REGEX.find(body)?.groupValues?.get(1)
        val helper = HELPER_REGEX.find(body)
        if (ig.isNullOrBlank() || iid.isNullOrBlank() || helper == null) {
            cachedSession = null
            throw TranslationFailure(
                TranslationDiagnostic(
                    "BING_SESSION", "Bing 翻译会话获取失败",
                    "检查网络或 Bing 接口地址；国内站默认 https://cn.bing.com。"
                )
            )
        }
        val session = Session(
            ig = ig,
            iid = iid,
            key = helper.groupValues[1],
            token = helper.groupValues[2],
            cookie = cookie
        )
        cachedSession = CachedSession(base, session, System.currentTimeMillis())
        return session
    }

    private suspend fun postText(base: String, session: Session, text: String, target: String): String {
        val url = "$base/ttranslatev3".toHttpUrl().newBuilder()
            .addQueryParameter("isVertical", "1")
            .addQueryParameter("IG", session.ig)
            .addQueryParameter("IID", session.iid)
            .build()
        val form = FormBody.Builder()
            .add("fromLang", "auto-detect")
            .add("to", target)
            .add("text", text)
            .add("token", session.token)
            .add("key", session.key)
            .build()
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "$base/translator")
        if (session.cookie.isNotBlank()) builder.header("Cookie", session.cookie)
        val body = executeRequest(builder.post(form).build(), sessionScoped = true)
        val translated = runCatching {
            json.parseToJsonElement(body).jsonArray
                .getOrNull(0)?.jsonObject
                ?.get("translations")?.jsonArray
                ?.getOrNull(0)?.jsonObject
                ?.get("text")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        if (translated.isNullOrBlank()) {
            throw TranslationFailure(
                TranslationDiagnostic("BING_EMPTY", "Bing 没有返回可用译文", "稍后重试，或改用其他翻译接口。")
            )
        }
        return translated
    }

    private suspend fun executeRequest(request: Request, sessionScoped: Boolean = false): String {
        return try {
            executeRaw(request).first
        } catch (e: HttpStatusFailure) {
            if (sessionScoped && e.status in setOf(401, 403)) {
                cachedSession = null
                throw SessionExpired()
            }
            throw TranslationFailure(TranslationErrors.http(e.status, e.detail))
        }
    }

    private suspend fun executeRequestWithHeaders(request: Request): Pair<String, String> {
        val (body, headers) = try {
            executeRaw(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpStatusFailure) {
            throw TranslationFailure(TranslationErrors.http(e.status, e.detail))
        }
        val cookie = headers.filter { it.first.equals("Set-Cookie", ignoreCase = true) }
            .mapNotNull { it.second.substringBefore(';').trim().takeIf(String::isNotBlank) }
            .joinToString("; ")
        return body to cookie
    }

    private suspend fun executeRaw(request: Request): Pair<String, List<Pair<String, String>>> {
        return suspendCancellableCoroutine { continuation ->
            val call: Call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val text = it.body.string()
                        if (!it.isSuccessful) {
                            continuation.resumeWithException(HttpStatusFailure(it.code, text.take(300)))
                        } else {
                            val headers = it.headers.map { header -> header.first to header.second }
                            continuation.resume(text to headers)
                        }
                    }
                }
            })
        }
    }

    private class HttpStatusFailure(val status: Int, val detail: String) : IOException("HTTP $status: $detail")
    private class SessionExpired : IOException("Bing session expired")
}
