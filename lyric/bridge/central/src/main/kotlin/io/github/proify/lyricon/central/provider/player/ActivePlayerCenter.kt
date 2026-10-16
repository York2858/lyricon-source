/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.central.provider.player

import io.github.proify.lyricon.central.CentralRuntime

/**
 * 活跃播放器状态的监听入口。
 *
 * 委托给中央运行时的活跃播放器中枢，供监听方（如本地中心应用）
 * 注册/注销 [ActivePlayerListener]。
 */
object ActivePlayerCenter {

    /**
     * 注册活跃播放器状态监听器。
     *
     * 若中央已存在活跃播放器，会立即收到一组当前状态快照。
     *
     * @param listener 状态监听器。
     */
    fun addListener(listener: ActivePlayerListener) {
        CentralRuntime.activePlayers.addListener(listener)
    }

    /**
     * 注销活跃播放器状态监听器。
     *
     * @param listener 之前注册的监听器。
     */
    fun removeListener(listener: ActivePlayerListener) {
        CentralRuntime.activePlayers.removeListener(listener)
    }
}
