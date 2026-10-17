package io.github.proify.lyricon.xposed.systemui.lyric

import kotlin.math.abs

/** A fresh gesture starts at every DOWN; repeated taps are never treated as double taps. */
internal class HandleGestureTracker(private val longPressSlop: Float, private val swipeDistance: Float) {
    enum class Action { TAP, SWIPE_LEFT, SWIPE_RIGHT }
    private var active = false
    private var longPressed = false
    private var canLongPress = false
    private var x = 0f
    private var y = 0f

    fun down(x: Float, y: Float) {
        this.x = x
        this.y = y
        active = true
        longPressed = false
        canLongPress = true
    }

    fun move(x: Float, y: Float): Boolean {
        if (abs(x - this.x) > longPressSlop || abs(y - this.y) > longPressSlop) canLongPress = false
        return active && canLongPress
    }

    fun longPress(): Boolean {
        if (!active || longPressed || !canLongPress) return false
        longPressed = true
        return true
    }

    /** Input can arrive in a batch after SystemUI was busy; use physical hold time at UP. */
    fun longPressAtUp(x: Float, y: Float, heldForMillis: Long, timeoutMillis: Long): Boolean {
        move(x, y)
        return heldForMillis >= timeoutMillis && longPress()
    }

    fun up(x: Float, y: Float): Action? {
        val wasActive = active && !longPressed
        cancel()
        if (!wasActive) return null
        val dx = x - this.x
        val dy = y - this.y
        return when {
            abs(dx) >= swipeDistance && abs(dx) > abs(dy) * 1.5f ->
                if (dx < 0) Action.SWIPE_LEFT else Action.SWIPE_RIGHT
            abs(dx) < swipeDistance && abs(dy) < swipeDistance -> Action.TAP
            else -> null
        }
    }

    fun cancel() {
        active = false
        canLongPress = false
    }
}
