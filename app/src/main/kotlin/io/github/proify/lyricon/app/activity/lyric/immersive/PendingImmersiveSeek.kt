package io.github.proify.lyricon.app.activity.lyric.immersive

import kotlin.math.abs

internal data class PendingImmersiveSeek(val identity: String, val target: Long, val submittedAt: Long) {
    fun isConfirmed(position: Long, playing: Boolean, now: Long): Boolean =
        playing && abs(position - (target + (now - submittedAt).coerceAtLeast(0))) <= 1500L

    fun shouldHold(currentIdentity: String, position: Long, playing: Boolean, now: Long): Boolean =
        currentIdentity == identity && now - submittedAt < 3000L && !isConfirmed(position, playing, now)
}
