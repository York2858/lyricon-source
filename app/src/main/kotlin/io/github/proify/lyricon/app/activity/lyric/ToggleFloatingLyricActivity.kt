/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.app.activity.lyric

import android.os.Bundle
import io.github.proify.lyricon.app.activity.BaseActivity
import io.github.proify.lyricon.app.receiver.LyricGestureReceiver

/**
 * 无界面 Activity，用于通过广播手动切换「悬浮窗歌词」开关。
 *
 * 由 `io.github.proify.lyricon.action.TOGGLE_FLOATING_LYRIC` 触发，
 * 切换完成后立即结束自身，不显示任何 UI。
 */
class ToggleFloatingLyricActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LyricGestureReceiver.toggle(this, intent.action)
        finish()
    }
}
