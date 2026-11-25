/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.xposed.systemui.hook

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.proify.lyricon.lyric.ai.core.AiConfigProviderImpl
import io.github.proify.lyricon.xposed.systemui.lyric.LyricPrefs
import java.util.concurrent.atomic.AtomicLong

/**
 * 允许 SystemUI 对用户配置的明文 AI 接口发起 HTTP 请求。
 *
 * 词幕 App 的 manifest 开启了 `usesCleartextTraffic`，因此词幕进程可以访问 `http://` 接口；
 * 但 SystemUI 默认禁止明文流量，OkHttp 会在建立连接前抛出
 * `CLEARTEXT communication to ... not permitted by network security policy`。
 *
 * 这里只放行当前 AI 配置（以及 Google 兜底地址）中显式使用 `http://` 的主机名，
 * 不影响 SystemUI 其他域名的明文策略。
 */
internal object CleartextHostPolicy {
    private const val TAG = "LyriconCleartext"
    private const val REFRESH_INTERVAL_MS = 2_000L

    @Volatile private var allowedHosts: Set<String> = emptySet()
    private val lastRefresh = AtomicLong(0L)
    private val loggedHosts = HashSet<String>()

    private val configProvider by lazy {
        AiConfigProviderImpl(LyricPrefs.baseStylePrefs) { LyricPrefs.baseStylePrefs }
    }

    fun initialize(module: XposedModule) {
        runCatching {
            val clazz = Class.forName("android.security.NetworkSecurityPolicy")
            val methods = buildList {
                runCatching {
                    add(clazz.getDeclaredMethod("isCleartextTrafficPermitted", String::class.java))
                }
                runCatching {
                    add(
                        clazz.getDeclaredMethod(
                            "isCleartextTrafficPermitted",
                            String::class.java,
                            Int::class.javaPrimitiveType
                        )
                    )
                }
            }
            check(methods.isNotEmpty()) { "isCleartextTrafficPermitted not found" }
            methods.forEach { method ->
                module.hook(method).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val host = chain.args.firstOrNull() as? String
                        if (host != null && permits(host)) return true
                        return chain.proceed()
                    }
                })
            }
            Log.i(TAG, "Cleartext whitelist hook installed")
        }.onFailure {
            Log.w(TAG, "Cannot install cleartext whitelist hook", it)
        }
    }

    private fun permits(host: String): Boolean {
        val normalized = normalize(host) ?: return false
        refreshIfNeeded()
        if (normalized !in allowedHosts) return false
        synchronized(loggedHosts) {
            if (loggedHosts.add(normalized)) Log.i(TAG, "Allowed cleartext HTTP for $normalized")
        }
        return true
    }

    private fun refreshIfNeeded() {
        val now = SystemClock.elapsedRealtime()
        val previous = lastRefresh.get()
        if (now - previous < REFRESH_INTERVAL_MS) return
        if (!lastRefresh.compareAndSet(previous, now)) return
        runCatching {
            val aiUrl = configProvider.getActiveConfig()?.baseUrl
            val googleUrl = LyricPrefs.baseStyle.googleTranslationBaseUrl
            val bingUrl = LyricPrefs.baseStyle.bingTranslationBaseUrl
            allowedHosts = listOfNotNull(aiUrl, googleUrl, bingUrl)
                .mapNotNull(::cleartextHost)
                .toSet()
        }.onFailure {
            Log.w(TAG, "Cannot refresh cleartext whitelist", it)
        }
    }

    private fun cleartextHost(url: String?): String? {
        val value = url?.trim().orEmpty()
        if (value.isEmpty()) return null
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return null
        if (!uri.scheme.equals("http", ignoreCase = true)) return null
        return normalize(uri.host)
    }

    private fun normalize(host: String?): String? = host
        ?.trim()
        ?.removePrefix("[")
        ?.removeSuffix("]")
        ?.lowercase()
        ?.takeIf { it.isNotEmpty() }
}
