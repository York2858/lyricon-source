/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.central.internal.player

/**
 * [ActivePlayerSelector.decide] 的一次决策结果。
 */
internal sealed interface ActivePlayerDecision {

    /** 事件来自非活跃来源且不满足切换条件：丢弃。 */
    data object Ignore : ActivePlayerDecision

    /** 事件来自当前活跃者：保持活跃身份，向监听方广播事件增量。 */
    data object Keep : ActivePlayerDecision

    /** 切换到来源会话：广播完整报告，并视情况附赠原始事件增量。 */
    data class Switch(
        val session: PlayerSession,
        val broadcastOriginal: Boolean,
    ) : ActivePlayerDecision
}
