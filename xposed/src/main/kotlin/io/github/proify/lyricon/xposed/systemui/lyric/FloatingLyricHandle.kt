/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.proify.lyricon.xposed.systemui.lyric

import android.content.Context
import android.content.res.Configuration
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import io.github.proify.android.extensions.dp
import io.github.proify.lyricon.statusbarlyric.StatusBarLyric.GestureType

/**
 * 覆盖在悬浮歌词图标上的透明触摸层，只负责接收手势；
 * 视觉完全由悬浮歌词自身的图标呈现，歌词窗口保持触摸穿透。
 */
internal class FloatingLyricHandle(
    context: Context,
    private val isControlEnabled: () -> Boolean,
    private val configurationChanged: () -> Unit
) : View(context) {
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val tracker = HandleGestureTracker(maxOf(touchSlop.toFloat(), 12.dp.toFloat()), 28.dp.toFloat())
    private val longPress = Runnable { if (tracker.longPress()) performLongClick() }

    init {
        isClickable = true
        setOnLongClickListener {
            if (!canInteract()) return@setOnLongClickListener false
            LyricGestureActions.dispatch(GestureType.LONG_PRESS, this)
            true
        }
    }

    override fun performClick(): Boolean {
        if (!canInteract()) return false
        super.performClick()
        LyricGestureActions.dispatch(GestureType.TAP, this)
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!canInteract()) {
            cancelGesture()
            return false
        }
        if (event.pointerCount > 1) {
            cancelGesture()
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isHapticFeedbackEnabled = LyricPrefs.gestureHapticEnabled
                removeCallbacks(longPress)
                tracker.down(event.rawX, event.rawY)
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracker.move(event.rawX, event.rawY)) removeCallbacks(longPress)
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                val action = tracker.up(event.rawX, event.rawY)
                if (action == HandleGestureTracker.Action.TAP) performClick()
                else if (action != null) {
                    LyricGestureActions.dispatch(if (action == HandleGestureTracker.Action.SWIPE_LEFT)
                        GestureType.SWIPE_LEFT else GestureType.SWIPE_RIGHT, this)
                    if (LyricPrefs.gestureHapticEnabled) {
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> cancelGesture()
        }
        return true
    }

    private fun canInteract(): Boolean = isAttachedToWindow && isControlEnabled()

    private fun cancelGesture() {
        removeCallbacks(longPress)
        tracker.cancel()
    }

    override fun onDetachedFromWindow() {
        // Remove pending long-press callbacks when the window disappears.
        cancelGesture()
        super.onDetachedFromWindow()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        post { if (isAttachedToWindow) configurationChanged() }
    }
}
