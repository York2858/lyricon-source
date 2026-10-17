/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.subscriber.internal.player

import android.os.Build
import android.content.Context
import android.os.SharedMemory
import android.util.Log
import androidx.annotation.RequiresApi
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.subscriber.ActivePlayerListener
import io.github.proify.lyricon.subscriber.IActivePlayerListener
import io.github.proify.lyricon.subscriber.ProviderInfo
import io.github.proify.lyricon.subscriber.util.ScreenStateMonitor
import io.github.proify.lyricon.subscriber.internal.wire.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 活跃播放器事件分发器。
 *
 * 作为 AIDL 回调接收中心服务推送的播放器事件，再分发给本地 [ActivePlayerListener]。
 * 播放进度通过 [SharedMemory] 读取；仅在播放、亮屏且有监听者时轮询。
 *
 * 单个监听器抛出的异常会被隔离并记录，不影响其他监听器与 AIDL 回调事务。
 */
@RequiresApi(Build.VERSION_CODES.O_MR1)
internal class PlayerEventDispatcher(context: Context) : IActivePlayerListener.Stub(),
    ScreenStateMonitor.ScreenStateListener {
    private val listeners = CopyOnWriteArraySet<ActivePlayerListener>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var positionMemory: SharedMemory? = null
    private var positionBuffer: ByteBuffer? = null
    private var positionJob: Job? = null
    private val positionLock = Any()
    private var playing = false
    private var lastPosition = Long.MIN_VALUE
    private var destroyed = false

    init {
        ScreenStateMonitor.initialize(context)
        ScreenStateMonitor.addListener(this)
    }

    override fun onScreenOn() = synchronized(positionLock) { updatePositionPoll() }
    override fun onScreenOff() = synchronized(positionLock) { updatePositionPoll() }
    override fun onScreenUnlocked() = onScreenOn()

    /** 注册本地活跃播放器监听器。 */
    fun registerActivePlayerListener(listener: ActivePlayerListener): Boolean =
        synchronized(positionLock) {
            if (destroyed) false else listeners.add(listener).also { updatePositionPoll() }
        }

    /** 移除本地活跃播放器监听器。 */
    fun unregisterActivePlayerListener(listener: ActivePlayerListener): Boolean =
        synchronized(positionLock) { listeners.remove(listener).also { updatePositionPoll() } }

    /** 更新远端提供的播放进度共享内存；null 表示停止位置轮询。 */
    fun setPositionSharedMemory(memory: SharedMemory?) = synchronized(positionLock) {
        detachPositionMemory()
        if (memory == null) return@synchronized
        if (destroyed || memory.size < Long.SIZE_BYTES) {
            memory.close()
            return@synchronized
        }

        positionMemory = memory
        positionBuffer = try {
            memory.mapReadOnly()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to map position memory", e)
            memory.close()
            positionMemory = null
            null
        }
        updatePositionPoll()
    }

    override fun onActiveProviderChanged(providerInfo: ByteArray?) {
        val info = providerInfo?.takeIf { it.isNotEmpty() }?.decode<ProviderInfo>()
        dispatch { it.onActiveProviderChanged(info) }
    }

    override fun onSongChanged(song: ByteArray?) {
        val value = song?.takeIf { it.isNotEmpty() }?.decode<Song>()
        dispatch { it.onSongChanged(value) }
    }

    override fun onPlaybackStateChanged(isPlaying: Boolean) {
        synchronized(positionLock) {
            playing = isPlaying
            updatePositionPoll()
        }
        dispatch { it.onPlaybackStateChanged(isPlaying) }
    }

    override fun onSeekTo(position: Long) {
        dispatch { it.onSeekTo(position) }
    }

    override fun onReceiveText(text: String?) {
        dispatch { it.onReceiveText(text) }
    }

    override fun onDisplayTranslationChanged(isDisplayTranslation: Boolean) {
        dispatch { it.onDisplayTranslationChanged(isDisplayTranslation) }
    }

    override fun onDisplayRomaChanged(isDisplayRoma: Boolean) {
        dispatch { it.onDisplayRomaChanged(isDisplayRoma) }
    }

    /**
     * 释放共享内存；[clearListeners] 为 true 时同时结束调度器生命周期。
     */
    fun release(clearListeners: Boolean = false) {
        synchronized(positionLock) {
            if (clearListeners) destroyed = true
            playing = false
            detachPositionMemory()
            if (clearListeners) listeners.clear()
        }
        if (!clearListeners) return

        scope.cancel()
        ScreenStateMonitor.removeListener(this)
    }

    /** 释放共享内存映射并停止位置轮询。 */
    private fun detachPositionMemory() {
        positionJob?.cancel()
        positionJob = null
        positionBuffer?.let { SharedMemory.unmap(it) }
        positionBuffer = null
        positionMemory?.close()
        positionMemory = null
        lastPosition = Long.MIN_VALUE
    }

    /** 根据播放、屏幕、监听者和连接状态决定是否需要轮询。 */
    private fun updatePositionPoll() {
        val shouldPoll = positionBuffer != null && playing && listeners.isNotEmpty() &&
                ScreenStateMonitor.state != ScreenStateMonitor.ScreenState.OFF
        if (!shouldPoll) {
            positionJob?.cancel()
            positionJob = null
        } else if (positionJob?.isActive != true) {
            lastPosition = Long.MIN_VALUE
            positionJob = scope.launch { readPositions() }
        }
    }

    private suspend fun readPositions() {
        val coroutineContext = currentCoroutineContext()
        while (true) {
            val position = synchronized(positionLock) {
                coroutineContext.ensureActive()
                val buffer = positionBuffer ?: return
                val value = buffer.getLong(0)
                if (value == lastPosition) null else value.also { lastPosition = it }
            }
            if (position != null) {
                dispatch { it.onPositionChanged(position) }
            }
            delay(POSITION_POLL_INTERVAL_MS)
        }
    }

    /** 向所有监听器分发事件，隔离单个监听器的异常。 */
    private inline fun dispatch(crossinline block: (ActivePlayerListener) -> Unit) {
        for (listener in listeners) {
            try {
                block(listener)
            } catch (e: Exception) {
                Log.e(TAG, "Dispatch failed for listener: " + listener.javaClass.name, e)
            }
        }
    }

    private inline fun <reified T> ByteArray.decode(): T? =
        runCatching { json.decodeFromString<T>(decodeToString()) }
            .onFailure { Log.e(TAG, "Failed to decode active-player payload", it) }
            .getOrNull()

    private companion object {
        private const val TAG = "PlayerEventDispatcher"
        private const val POSITION_POLL_INTERVAL_MS = 50L
    }
}
