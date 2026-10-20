package io.github.proify.lyricon.lyric.ai.translate

import kotlinx.serialization.Serializable
import java.net.*
import java.io.IOException
import javax.net.ssl.SSLException

@Serializable
data class TranslationDiagnostic(val code: String, val reason: String, val suggestion: String, val httpStatus: Int = 0)

class TranslationFailure(val diagnostic: TranslationDiagnostic) : IOException(diagnostic.reason)

object TranslationErrors {
    fun http(status: Int, detail: String = "", secret: String? = null): TranslationDiagnostic {
        val clean = redact(detail, secret)
        val (reason, advice) = when (status) {
            400 -> "请求参数被服务拒绝" to "检查模型名称、接口地址及该模型是否支持 JSON 输出。"
            401 -> "API Key 无效或已过期" to "到 AI 实验室检查当前提供商的 API Key。"
            402 -> "账户余额不足或需要付费" to "检查服务商账户余额和计费状态。"
            403 -> "当前账户没有访问权限" to "检查模型权限、账户状态及服务商地区限制。"
            404 -> "接口地址或模型不存在" to "检查 Base URL 和模型名称，确认服务商支持此接口。"
            408, 504 -> "翻译服务响应超时" to "检查网络连接，稍后重试或更换可用的服务。"
            429 -> if (Regex("(?i)quota|balance|credit|billing").containsMatchIn(clean))
                "账户额度或余额不足" to "检查服务商账户额度、余额及计费设置。"
                else "请求过于频繁，或已达到服务限额" to "稍后重试，并检查服务商的速率与额度限制。"
            in 500..599 -> "翻译服务暂时故障" to "稍后重试，或更换翻译服务。"
            else -> "翻译请求被服务拒绝" to "检查网络和服务配置，稍后重试。"
        }
        return TranslationDiagnostic("HTTP_$status", "$reason（HTTP $status）" + if (clean.isBlank()) "" else "\n服务返回：$clean", advice, status)
    }
    fun from(error: Throwable, secret: String? = null): TranslationDiagnostic {
        val causes = generateSequence(error) { it.cause }.take(12).toList()
        causes.filterIsInstance<TranslationFailure>().firstOrNull()?.let { return it.diagnostic }
        val root = causes.last()
        return when {
            causes.any { it is SocketTimeoutException || it.javaClass.simpleName.contains("Timeout") } ->
                TranslationDiagnostic("TIMEOUT", "连接或读取翻译结果超时", "检查网络、代理和接口响应速度，稍后重试。")
            causes.any { it is UnknownHostException } -> TranslationDiagnostic("DNS", "无法解析翻译服务地址", "检查接口域名、网络和 DNS；Google 翻译需要可访问 Google 的网络。")
            causes.any { it is SSLException } -> TranslationDiagnostic("TLS", "无法建立安全连接或证书校验失败", "检查系统时间、代理证书和接口的 HTTPS 配置。")
            causes.any { it is ConnectException } -> TranslationDiagnostic("CONNECT", "无法连接翻译服务", "检查网络、代理、接口地址和端口。")
            causes.any { it.javaClass.simpleName.contains("Json") || it.javaClass.simpleName.contains("Serialization") } ->
                TranslationDiagnostic("FORMAT", "翻译服务返回的数据无法解析", "检查接口地址；服务可能返回了验证页面或不兼容的数据格式。")
            causes.any { it is IllegalArgumentException } -> TranslationDiagnostic("CONFIG", "翻译配置或请求参数不正确", "检查接口地址、模型和目标语言。")
            else -> TranslationDiagnostic("REQUEST", "翻译请求失败（${root.javaClass.simpleName}）" +
                root.message?.let { "\n${redact(it, secret)}" }.orEmpty(), "检查网络及服务配置，稍后重试。")
        }
    }
    fun googleHttp(status: Int) = TranslationDiagnostic("GOOGLE_HTTP_$status", when (status) {
        403 -> "Google 拒绝访问（HTTP 403），可能受到网络或地区限制"
        429 -> "Google 请求过于频繁（HTTP 429）"
        404 -> "Google 翻译接口地址不存在（HTTP 404）"
        in 500..599 -> "Google 翻译服务暂时故障（HTTP $status）"
        else -> "Google 翻译请求失败（HTTP $status）"
    }, "检查 Google 接口地址及网络可达性，稍后重试。此接口无需填写 AI 的 API Key。", status)
    fun redact(value: String, secret: String? = null, limit: Int = 500): String {
        var result = value
        if (!secret.isNullOrBlank()) result = result.replace(secret, "[已隐藏]")
        return result.replace(Regex("(?i)Bearer\\s+[^\\s\"']+"), "Bearer [已隐藏]")
            .replace(Regex("sk-[A-Za-z0-9_-]{8,}"), "[已隐藏]")
            .replace(Regex("(?i)(api[_-]?key|authorization|token)([\\s\"':=]+)[^\\s\"',}&]+"), "$1$2[已隐藏]")
            .replace(Regex("[\\r\\n\\t]+"), " ").take(limit)
    }
}
