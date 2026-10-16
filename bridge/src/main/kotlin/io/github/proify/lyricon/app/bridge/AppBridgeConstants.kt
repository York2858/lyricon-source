/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.app.bridge

object AppBridgeConstants {
    /**
     * 切换「状态栏歌词 / 悬浮窗歌词」显示模式的入口 Action。
     *
     * 由 App 侧 [io.github.proify.lyricon.app.activity.lyric.ToggleFloatingLyricActivity] 处理
     * （无界面 Activity，写入偏好后立即结束并回传样式更新通知）。
     */
    const val ACTION_TOGGLE_FLOATING_LYRIC: String =
        "io.github.proify.lyricon.action.TOGGLE_FLOATING_LYRIC"

    const val REQUEST_UPDATE_LYRIC_STYLE: String = "request_update_lyric_style"
    const val REQUEST_CLEAR_TRANSLATION_DB: String = "request_clear_translation_db"
    const val REQUEST_HIGHLIGHT_VIEW: String = "request_highlight_view"
    const val REQUEST_CHECK_SAFE_MODE: String = "request_check_safe_mode"
    const val REQUEST_VIEW_TREE: String = "request_view_tree"
}