/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

@file:Suppress("unused")

package io.github.proify.lyricon.central.internal.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 屏幕状态监视器：注册系统屏幕广播并分发状态变化。
 */
internal object ScreenStateMonitor {
    private const val TAG = "ScreenStateMonitor"

    private val listeners = CopyOnWriteArraySet<ScreenStateListener>()
    private var appContext: Context? = null
    private var receiver: BroadcastReceiver? = null

    /** 当前屏幕状态。 */
    @Volatile
    var state: ScreenState = ScreenState.UNKNOWN
        private set

    /** 屏幕状态。 */
    enum class ScreenState {
        /** 未知。 */
        UNKNOWN,

        /** 亮屏/解锁中。 */
        ON,

        /** 灭屏。 */
        OFF
    }

    /** 屏幕状态监听接口。 */
    interface ScreenStateListener {
        /** 亮屏时触发。 */
        fun onScreenOn()

        /** 灭屏时触发。 */
        fun onScreenOff()

        /** 解锁时触发。 */
        fun onScreenUnlocked()
    }

    /**
     * 初始化并注册系统屏幕广播（幂等）。
     *
     * @param context 应用上下文。
     */
    fun initialize(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        registerReceiver()
    }

    /** 注册监听器。 */
    fun addListener(listener: ScreenStateListener) {
        listeners += listener
    }

    /** 注销监听器。 */
    fun removeListener(listener: ScreenStateListener) {
        listeners -= listener
    }

    /** 解除广播注册并清空监听器。 */
    fun release() {
        val ctx = appContext ?: return
        runCatching {
            receiver?.let { ctx.unregisterReceiver(it) }
        }
        listeners.clear()
        receiver = null
        appContext = null
    }

    /** 注册系统屏幕广播接收器。 */
    private fun registerReceiver() {
        val ctx = appContext ?: return

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }

        receiver = ScreenReceiver()
        ContextCompat.registerReceiver(
            ctx,
            receiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    /** 屏幕广播接收器。 */
    private class ScreenReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> onScreenOn()
                Intent.ACTION_SCREEN_OFF -> onScreenOff()
                Intent.ACTION_USER_PRESENT -> onScreenUnlocked()
            }
        }
    }

    /** 亮屏事件入口。 */
    internal fun onScreenOn() {
        state = ScreenState.ON
        dispatch { it.onScreenOn() }
    }

    /** 灭屏事件入口。 */
    internal fun onScreenOff() {
        state = ScreenState.OFF
        dispatch { it.onScreenOff() }
    }

    /** 解锁事件入口。 */
    internal fun onScreenUnlocked() {
        state = ScreenState.ON
        dispatch { it.onScreenUnlocked() }
    }

    /** 向所有监听器分发事件（隔离单点异常）。 */
    private inline fun dispatch(action: (ScreenStateListener) -> Unit) {
        listeners.forEach {
            try {
                action(it)
            } catch (e: Exception) {
                Log.e(TAG, "Listener dispatch error", e)
            }
        }
    }
}
