/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.central.internal.connection

/**
 * 远端连接抽象：与对端进程的注册 Binder 生命周期。
 *
 * @property K 连接身份类型。
 */
internal interface RemoteConnection<K> {
    /** 连接身份（注册 key）。 */
    val key: K

    /**
     * 注入 Binder 死亡回调；null 表示清除。
     *
     * 死亡回调由 [ConnectionRegistry] 在注册时注入。
     */
    fun setDeathRecipient(onDeath: (() -> Unit)?)

    /** 关闭连接并释放资源。 */
    fun close()
}
