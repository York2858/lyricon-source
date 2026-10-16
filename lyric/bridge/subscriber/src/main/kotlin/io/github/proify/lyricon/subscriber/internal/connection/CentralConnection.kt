/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.subscriber.internal.connection

import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import io.github.proify.lyricon.subscriber.ActivePlayerListener
import io.github.proify.lyricon.subscriber.IRemoteService
import io.github.proify.lyricon.subscriber.internal.player.PlayerEventDispatcher

/**
 * 订阅端与中心服务的连接端点。
 *
 * 负责维护中心服务返回的 [IRemoteService]、监听 Binder 死亡，并桥接活跃播放器事件。
 */
@RequiresApi(Build.VERSION_CODES.O_MR1)
internal class CentralConnection(
    onRemoteDied: () -> Unit,
) {
    private var remoteService: IRemoteService? = null
    private val eventDispatcher = PlayerEventDispatcher()
    private val deathRecipient = IBinder.DeathRecipient(onRemoteDied)

    /** 绑定新的远端服务，旧服务会自动解除死亡监听。 */
    fun bind(service: IRemoteService?) {
        val oldService = remoteService
        remoteService = service

        runCatching {
            oldService?.asBinder()?.unlinkToDeath(deathRecipient, 0)
        }.onFailure { Log.e(TAG, "Failed to unlink old remote service", it) }

        runCatching {
            service?.setActivePlayerListener(eventDispatcher)
            eventDispatcher.setPositionSharedMemory(service?.activePlayerPositionMemory)
            service?.asBinder()?.linkToDeath(deathRecipient, 0)
        }.onFailure { Log.e(TAG, "Failed to bind remote service", it) }
    }

    /** 添加活跃播放器监听器。 */
    fun addActivePlayerListener(listener: ActivePlayerListener): Boolean =
        eventDispatcher.registerActivePlayerListener(listener)

    /** 移除活跃播放器监听器。 */
    fun removeActivePlayerListener(listener: ActivePlayerListener): Boolean =
        eventDispatcher.unregisterActivePlayerListener(listener)

    /**
     * 断开当前远端服务。
     *
     * @param notifyRemote 是否调用远端 disconnect 接口。
     * @param destroy 是否彻底销毁活跃播放器分发器。
     */
    fun disconnect(notifyRemote: Boolean = true, destroy: Boolean = false) {
        val service = remoteService
        if (notifyRemote) runCatching { service?.disconnect() }
            .onFailure { Log.e(TAG, "Failed to disconnect remote service", it) }
        eventDispatcher.release(clearListeners = destroy)
        bind(null)
    }

    private companion object {
        private const val TAG = "CentralConnection"
    }
}
