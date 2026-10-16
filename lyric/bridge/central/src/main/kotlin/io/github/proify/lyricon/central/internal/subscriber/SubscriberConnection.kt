/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.central.internal.subscriber

import android.os.IBinder
import io.github.proify.lyricon.central.internal.binding.SubscriberServiceStub
import io.github.proify.lyricon.central.internal.connection.BinderDeathTracker
import io.github.proify.lyricon.central.internal.connection.RemoteConnection
import io.github.proify.lyricon.subscriber.ISubscriberBinder
import io.github.proify.lyricon.subscriber.SubscriberInfo
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 订阅端连接：与一个订阅端进程的注册 Binder 生命周期。
 *
 * 持有其远端服务桩；Binder 死亡时经 [BinderDeathTracker] 身份校验后拆除。
 *
 * @property subscriberInfo 订阅端身份。
 */
internal class SubscriberConnection(
    binder: ISubscriberBinder,
    val subscriberInfo: SubscriberInfo,
) : RemoteConnection<SubscriberInfo> {

    override val key: SubscriberInfo get() = subscriberInfo

    val service = SubscriberServiceStub(this)

    /** 当前有效的注册 Binder；死亡回调据此判断失效是否仍然成立。 */
    @Volatile
    private var binder: ISubscriberBinder? = binder

    /** 注册表注入的拆除回调：从注册表移除并关闭连接。 */
    private var onDeathHook: (() -> Unit)? = null

    private val deathTracker = BinderDeathTracker()
    private val closed = AtomicBoolean(false)

    init {
        deathTracker.onDeath = ::onBinderDeath
        deathTracker.track(binder.asBinder())
    }

    /**
     * 重新绑定注册 Binder（订阅端以新广播再次注册时调用）。
     *
     * 旧 Binder 的迟到死亡事件不再拆除连接；连接已关闭时返回 false，
     * 调用方应改为创建新的连接。
     */
    fun reattach(newBinder: ISubscriberBinder): Boolean = synchronized(this) {
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
     * 身份校验与拆除在同一把锁内完成，避免旧 Binder 的迟到死亡通知
     * 拆除已被新注册替代的连接。
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
    }

    override fun toString() = "SubscriberConnection{$subscriberInfo}"
}
