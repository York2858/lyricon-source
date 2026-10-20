package io.github.proify.lyricon.lyric.ai.translate

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Lyrico's gtx endpoint, 1500-character batches and per-line retry when lines are merged. */
object GoogleLyricsTranslator {
    const val DEFAULT_URL = "https://translate.googleapis.com/translate_a/single"
    private val http = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS).callTimeout(35, TimeUnit.SECONDS).build()

    suspend fun translate(texts: List<String>, language: String?, baseUrl: String = DEFAULT_URL): List<String> =
        translateWith(texts) { text -> requestText(text, language, baseUrl) }

    suspend fun translateWith(texts: List<String>, request: suspend (String) -> String): List<String> {
        val output = mutableListOf<String>()
        for (batch in batches(texts)) {
            val translated = request(batch.joinToString("\n")).replace("\r\n", "\n").split('\n')
            val rows = if (translated.size == batch.size) translated else batch.map { if (it.isBlank()) it else request(it) }
            if (rows.any { it.isBlank() }) throw TranslationFailure(TranslationDiagnostic("GOOGLE_EMPTY", "Google 返回了空译文", "稍后重试或检查目标语言。"))
            output += rows
        }
        return output
    }
    fun batches(texts: List<String>): List<List<String>> {
        val result = mutableListOf<List<String>>(); var current = mutableListOf<String>(); var size = 0
        texts.forEach { text ->
            if (current.isNotEmpty() && size + text.length + 1 > 1500) { result += current; current = mutableListOf(); size = 0 }
            current += text; size += text.length + if (current.size > 1) 1 else 0
        }
        if (current.isNotEmpty()) result += current
        return result
    }
    fun targetLanguage(raw: String?): String {
        val value = raw.orEmpty().trim()
        if (value.isEmpty()) return Locale.getDefault().toLanguageTag()
        val lower = value.lowercase(Locale.ROOT)
        return when {
            lower in listOf("zh-tw", "zh-hant", "zh-hant-tw") || value.contains("繁体") || value.contains("繁體") -> "zh-TW"
            lower.startsWith("zh") || value.contains("中文") || value.contains("汉语") || value.contains("漢語") || lower.contains("chinese") -> "zh-CN"
            value.contains("英语") || value.contains("英文") || lower.startsWith("english") -> "en"
            value.contains("日语") || value.contains("日文") || lower == "japanese" -> "ja"
            value.contains("韩语") || value.contains("韩文") || lower == "korean" -> "ko"
            value.contains("法语") || lower == "french" -> "fr"
            value.contains("德语") || lower == "german" -> "de"
            value.contains("俄语") || lower == "russian" -> "ru"
            value.contains("西班牙") || lower == "spanish" -> "es"
            value.contains("葡萄牙") || lower == "portuguese" -> "pt"
            Regex("[a-zA-Z]{2,3}(-[a-zA-Z]{2,4})?").matches(value) -> value
            else -> throw TranslationFailure(TranslationDiagnostic("GOOGLE_LANGUAGE", "Google 不支持目标语言“${value.take(40)}”", "将目标语言改为中文、英语、日语等受支持语言。Google 无法执行 AI 的风格提示词。"))
        }
    }
    fun parse(body: String): String = Json.parseToJsonElement(body).jsonArray.getOrNull(0)?.jsonArray.orEmpty()
        .joinToString("") { it.jsonArray.getOrNull(0)?.jsonPrimitive?.contentOrNull.orEmpty() }
        .takeIf { it.isNotBlank() } ?: throw TranslationFailure(TranslationDiagnostic("GOOGLE_EMPTY", "Google 未返回有效译文", "检查网络和接口地址，稍后重试。"))

    private suspend fun requestText(text: String, language: String?, baseUrl: String): String {
        if (text.isBlank()) return text
        val url = baseUrl.toHttpUrl().newBuilder().addQueryParameter("client", "gtx")
            .addQueryParameter("sl", "auto").addQueryParameter("tl", targetLanguage(language))
            .addQueryParameter("dt", "t").addQueryParameter("q", text).build()
        val call = http.newCall(Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build())
        val body = suspendCancellableCoroutine<String> { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            val body = it.body.string()
                            if (!it.isSuccessful) throw TranslationFailure(TranslationErrors.googleHttp(it.code))
                            if (continuation.isActive) continuation.resume(body)
                        } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
                    }
                }
            })
        }
        return try { parse(body) } catch (e: TranslationFailure) { throw e }
        catch (_: Exception) { throw TranslationFailure(TranslationDiagnostic("GOOGLE_FORMAT", "Google 返回的数据不是有效翻译结果", "检查 Google 接口地址、代理和网络是否返回了验证页面。")) }
    }
}
