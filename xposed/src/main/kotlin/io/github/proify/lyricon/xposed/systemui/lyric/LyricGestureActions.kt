/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.proify.lyricon.xposed.systemui.lyric

import android.content.Intent
import android.util.Log
import android.view.View
import io.github.proify.lyricon.app.bridge.AppBridge.LyricGesturePrefs
import io.github.proify.lyricon.app.bridge.AppBridgeConstants
import io.github.proify.lyricon.common.PackageNames
import io.github.proify.lyricon.statusbarlyric.StatusBarLyric.GestureType
import io.github.proify.lyricon.xposed.logger.YLog
import io.github.proify.lyricon.xposed.systemui.lyric.control.LyricControlPopup

/** One action mapping shared by status bar lyrics and the floating control dot. */
internal object LyricGestureActions {
    fun dispatch(gesture: GestureType, anchor: View) {
        if (!LyricPrefs.gestureEnabled) return
        val (key, default) = when (gesture) {
            GestureType.TAP -> LyricGesturePrefs.KEY_TAP to LyricGesturePrefs.DEFAULT_TAP
            GestureType.LONG_PRESS -> LyricGesturePrefs.KEY_LONG_PRESS to LyricGesturePrefs.DEFAULT_LONG_PRESS
            GestureType.SWIPE_LEFT -> LyricGesturePrefs.KEY_SWIPE_LEFT to LyricGesturePrefs.DEFAULT_SWIPE_LEFT
            GestureType.SWIPE_RIGHT -> LyricGesturePrefs.KEY_SWIPE_RIGHT to LyricGesturePrefs.DEFAULT_SWIPE_RIGHT
        }
        runCatching {
            val action = LyricPrefs.gestureAction(key, default)
            Log.i("LyriconGesture", "Dispatch $gesture action=$action")
            when (action) {
                LyricGesturePrefs.ACTION_NONE -> Unit
                LyricGesturePrefs.ACTION_TOGGLE_PLAY -> PlaybackControl.togglePlay()
                LyricGesturePrefs.ACTION_PREVIOUS -> PlaybackControl.previous()
                LyricGesturePrefs.ACTION_NEXT -> PlaybackControl.next()
                LyricGesturePrefs.ACTION_OPEN_CONTROL -> LyricControlPopup.show(anchor)
                LyricGesturePrefs.ACTION_SWITCH_LYRIC_MODE -> FloatingLyricController.toggleLyricDisplayMode()
                LyricGesturePrefs.ACTION_TOGGLE_LYRICS -> anchor.context.sendBroadcast(
                    Intent(AppBridgeConstants.ACTION_TOGGLE_LYRICS)
                        .setClassName(PackageNames.APPLICATION, AppBridgeConstants.LYRIC_GESTURE_RECEIVER)
                )
            }
        }.onFailure { YLog.error("LyricGestureActions", "Gesture action failed", it) }
    }
}
