/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

@file:Suppress("SameReturnValue")

package io.github.proify.lyricon.central.internal

/**
 * 中央模块常量。
 *
 * 广播动作与 Provider/Subscriber 库侧的常量值保持一致（跨进程握手契约）。
 */
internal object CentralConstants {

    /** 调试开关。 */
    fun isDebug(): Boolean = false

    /** 注册提供端广播动作。 */
    internal const val ACTION_REGISTER_PROVIDER: String =
        "io.github.proify.lyricon.lyric.bridge.REGISTER_PROVIDER"

    /** 注册订阅端广播动作。 */
    internal const val ACTION_REGISTER_SUBSCRIBER: String =
        "io.github.proify.lyricon.lyric.bridge.REGISTER_SUBSCRIBER"

    /** 中央服务启动完成广播动作。 */
    internal const val ACTION_CENTRAL_BOOT_COMPLETED: String =
        "io.github.proify.lyricon.lyric.bridge.CENTRAL_BOOT_COMPLETED"

    /** 广播中承载 Binder 的 Bundle key。 */
    internal const val EXTRA_BUNDLE: String = "bundle"

    /** Bundle 中注册 Binder 的 key。 */
    internal const val EXTRA_BINDER: String = "binder"
}
