/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.central.internal.player

import android.os.SystemClock

/**
 * 播放位置跟踪器：统一"手动上报位置"与"PlaybackState 驱动"两种模式的计算。
 *
 * 纯 Kotlin + 时钟注入（默认 [SystemClock.elapsedRealtime]），可单元测试。
 *
 * - 手动模式：位置来自共享内存读取器（[manualPositionReader]）；
 * - 驱动模式：位置由最近一次 [usePlaybackState] 记录的基点、时间与速度推算。
 */
internal class PlaybackStateTracker(
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) {

    /** 当前是否处于 PlaybackState 驱动模式。 */
    @Volatile
    var isStateDriven: Boolean = false
        private set

    @Volatile
    private var isPlaying: Boolean = false

    @Volatile
    private var basePosition: Long = 0L

    @Volatile
    private var lastUpdate: Long = 0L

    @Volatile
    private var speed: Float = 1.0f

    /** 手动模式的读取源（位置共享内存）。 */
    @Volatile
    var manualPositionReader: (() -> Long)? = null

    /** 切换到手动上报模式（setPlaybackState(Boolean) 路径）。 */
    fun useManualMode() {
        isStateDriven = false
    }

    /** 切换到 PlaybackState 驱动模式（setPlaybackState2 路径）。 */
    fun usePlaybackState(
        playing: Boolean,
        position: Long,
        lastUpdateTime: Long,
        playbackSpeed: Float,
    ) {
        isStateDriven = true
        isPlaying = playing
        basePosition = position
        lastUpdate = lastUpdateTime
        speed = playbackSpeed
    }

    /**
     * 停止驱动并清空记录。
     *
     * @return 是否正处于驱动模式（调用方据此决定是否关闭位置更新）。
     */
    fun stopDriving(): Boolean {
        val wasDriving = isStateDriven
        isStateDriven = false
        isPlaying = false
        basePosition = 0L
        lastUpdate = 0L
        speed = 1.0f
        return wasDriving
    }

    /** 当前播放位置（毫秒），非负。 */
    fun computePosition(): Long {
        if (!isStateDriven) {
            return (manualPositionReader?.invoke() ?: 0L).coerceAtLeast(0L)
        }

        val safeBase = basePosition.coerceAtLeast(0L)
        if (!isPlaying || lastUpdate <= 0L) return safeBase

        val delta = (clock() - lastUpdate).coerceAtLeast(0L)
        val advanced = if (speed == 1.0f) {
            safeBase + delta
        } else {
            safeBase + (delta * speed).toLong()
        }
        return advanced.coerceAtLeast(0L)
    }
}
