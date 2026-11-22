package io.github.proify.lyricon.app.activity.lyric.immersive.halcyon

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos

@Composable
internal fun rememberSharedFlowProgress(
    durationMillis: Int,
    animate: Boolean,
    reverse: Boolean = false,
    fallback: Float = 0f,
    // Keep the animation clock on the display frame cadence. These backgrounds are intentionally
    // soft, but dropping updates to a timer cadence makes the flow visibly step on 60/120 Hz
    // devices. Callers may still opt into a cap when a future surface is explicitly static.
    frameIntervalMs: Long = 0L
): Float {
    // Freeze the animation clock whenever the player surface is slid off-screen but still resident.
    val effectiveAnimate = animate && LocalPlayerSurfaceActive.current
    var frameTimeNs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(durationMillis, effectiveAnimate, frameIntervalMs) {
        if (!effectiveAnimate) {
            frameTimeNs = 0L
            return@LaunchedEffect
        }
        val intervalNs = frameIntervalMs * 1_000_000L
        var lastPublishedNs = 0L
        while (true) {
            val now = withFrameNanos { it }
            // 按时间桶限帧：在 60/120 Hz 上都能稳定落在目标帧率附近。
            if (intervalNs <= 0L || lastPublishedNs == 0L || now - lastPublishedNs >= intervalNs) {
                frameTimeNs = now
                lastPublishedNs = now
            }
        }
    }
    if (!effectiveAnimate || frameTimeNs == 0L) return fallback
    val safeDuration = durationMillis.coerceAtLeast(1)
    val elapsedMs = frameTimeNs / 1_000_000L
    val cycle = elapsedMs / safeDuration
    val fraction = (elapsedMs % safeDuration).toFloat() / safeDuration.toFloat()
    return if (reverse && cycle % 2L == 1L) 1f - fraction else fraction
}
