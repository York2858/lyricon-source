/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.central.internal.provider

import android.os.IBinder
import io.github.proify.lyricon.central.internal.binding.RemoteServiceStub
import io.github.proify.lyricon.central.internal.connection.BinderDeathTracker
import io.github.proify.lyricon.central.internal.connection.RemoteConnection
import io.github.proify.lyricon.central.internal.player.ActivePlayerHub
import io.github.proify.lyricon.provider.IProviderBinder
import io.github.proify.lyricon.provider.ProviderInfo
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 提供端连接：与一个提供端进程的注册 Binder 生命周期。
 *
 * 持有其远端服务桩；Binder 死亡时经 [BinderDeathTracker] 身份校验后
 * 拆除并通知活跃播放器中枢。
 *
 * @property providerInfo 提供端身份。
 * @property activePlayers 活跃播放器中枢（断开时释放）。
 */
internal class ProviderConnection(
    binder: IProviderBinder,
    val providerInfo: ProviderInfo,
    private val activePlayers: ActivePlayerHub,
) : RemoteConnection<ProviderInfo> {

    override val key: ProviderInfo get() = providerInfo

    val service = RemoteServiceStub(this)

    /** 当前有效的注册 Binder；死亡回调据此判断失效是否仍然成立。 */
    @Volatile
    private var binder: IProviderBinder? = binder

    /** 注册表注入的拆除回调：从注册表移除并关闭连接。 */
    private var onDeathHook: (() -> Unit)? = null

    private val deathTracker = BinderDeathTracker()
    private val closed = AtomicBoolean(false)

    init {
        deathTracker.onDeath = ::onBinderDeath
        deathTracker.track(binder.asBinder())
    }

    /**
     * 重新绑定注册 Binder（提供端以新广播再次注册时调用）。
     *
     * 旧 Binder 的迟到死亡事件不再拆除连接；连接已关闭时返回 false，
     * 调用方应改为创建新的连接。
     */
    fun reattach(newBinder: IProviderBinder): Boolean = synchronized(this) {
        if (closed.get()) return false
        binder = newBinder
        deathTracker.track(newBinder.asBinder())
        true
    }

    override fun setDeathRecipient(onDeath: (() -> Unit)?) {
        synchronized(this) {
            onDeathHook = onDeath
        }
    }

    /**
     * Binder 死亡回调（binder 线程）。
     *
     * 身份校验与拆除在同一把锁内完成：若重新注册恰好先完成，此处的死亡事件
     * 将被忽略；若死亡先发生，则随后的重注册会新建连接。两种时序都不会留下
     * "看似连接、实则已失效"的粘性死亡连接。
     */
    private fun onBinderDeath(deadBinder: IBinder) {
        synchronized(this) {
            if (closed.get()) return
            if (binder?.asBinder() !== deadBinder) return

            val hook = onDeathHook
            if (hook != null) {
                hook()
            } else {
                close()
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(this) {
            deathTracker.detach()
            binder = null
        }
        service.close()
        activePlayers.notifyProviderInvalid(providerInfo)
    }

    override fun toString() = "ProviderConnection{$providerInfo}"
}
