/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.lyric.view.line

import android.view.Choreographer

/**
 * 基于 [Choreographer] 的帧调度器：与 [LyricLineView] 解耦。
 *
 * 每帧回调 [onFrame]（返回本帧是否有内容变化）；有变化时调用 [requestInvalidate] 请求重绘；
 * 当 [shouldRun] 不再满足（例如 View 脱离窗口）时自动停止。
 */
internal class FrameAnimator(
    private val onFrame: (deltaNanos: Long) -> Boolean,
    private val requestInvalidate: () -> Unit,
    private val shouldRun: () -> Boolean,
    private val needsFrame: () -> Boolean,
    private val enqueue: (Choreographer.FrameCallback) -> Unit = {
        Choreographer.getInstance().postFrameCallback(it)
    },
    private val remove: (Choreographer.FrameCallback) -> Unit = {
        Choreographer.getInstance().removeFrameCallback(it)
    },
) : Choreographer.FrameCallback {

    private var running = false
    private var lastFrameNanos = 0L
    private var generation = 0

    val isRunning: Boolean get() = running

    fun startIfNeeded() {
        if (!running && shouldRun() && needsFrame()) {
            running = true
            generation++
            lastFrameNanos = 0L
            enqueue(this)
        }
    }

    fun stop() {
        running = false
        generation++
        remove(this)
        lastFrameNanos = 0L
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running || !shouldRun()) {
            running = false
            return
        }

        val deltaNanos = if (lastFrameNanos == 0L) 0L else frameTimeNanos - lastFrameNanos
        lastFrameNanos = frameTimeNanos
        val frameGeneration = generation

        if (onFrame(deltaNanos)) requestInvalidate()
        if (frameGeneration != generation) return

        if (running && shouldRun() && needsFrame()) {
            enqueue(this)
        } else {
            running = false
            lastFrameNanos = 0L
        }
    }
}
