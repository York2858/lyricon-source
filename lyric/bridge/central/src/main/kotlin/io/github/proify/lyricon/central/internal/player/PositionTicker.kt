/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.central.internal.player

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield

/**
 * 播放位置轮询器：按固定间隔读取位置并回调。
 *
 * 使用 elapsedRealtime 校正漂移；start/stop 可被幂等地重复调用，
 * 内部用 Mutex 保证任务切换的原子性。
 *
 * @property scope 宿主协程作用域。
 * @property readPosition 位置读取源。
 * @property onPosition 每次读到位置后的回调。
 */
internal class PositionTicker(
    private val scope: CoroutineScope,
    private val readPosition: () -> Long,
    private val onPosition: (Long) -> Unit
) {

    private val mutex = Mutex()

    @Volatile
    private var job: Job? = null

    /**
     * 开始轮询（若已在运行则忽略）。
     *
     * @param interval 轮询间隔（毫秒），最小 16ms。
     */
    fun start(interval: Long) {
        if (job?.isActive == true) return

        scope.launch {
            mutex.withLock {
                if (job?.isActive == true) return@withLock

                job = scope.launch {
                    val safeInterval = interval.coerceAtLeast(MIN_INTERVAL_MS)
                    var nextTick = SystemClock.elapsedRealtime()

                    while (isActive) {
                        onPosition(readPosition())
                        nextTick += safeInterval

                        val remaining = nextTick - SystemClock.elapsedRealtime()
                        if (remaining > 0) {
                            delay(remaining)
                        } else {
                            nextTick = SystemClock.elapsedRealtime()
                            yield()
                        }
                    }
                }
            }
        }
    }

    /** 停止轮询（若未运行则为空操作）。 */
    fun stop() {
        val current = job
        current?.cancel()

        if (current != null) {
            scope.launch {
                mutex.withLock {
                    if (job === current) job = null
                }
            }
        }
    }

    /** 停止轮询并释放资源。 */
    fun close() {
        stop()
    }

    private companion object {
        private const val MIN_INTERVAL_MS = 16L
    }
}
