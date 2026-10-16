/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.app.service

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.edit
import io.github.proify.lyricon.app.util.LyricPrefs

/**
 * 「悬浮窗歌词」快捷设置磁贴。
 *
 * 读取/写入悬浮窗基础样式偏好中的手动开关，并同步磁贴状态。
 */
class FloatingLyricTileService : TileService() {

    private val key = "lyric_style_base_manual_floating_lyric_enabled"

    override fun onStartListening() {
        super.onStartListening()
        syncTileState()
    }

    override fun onClick() {
        super.onClick()
        val prefs = LyricPrefs.floatingBasicStylePrefs
        val enabled = !prefs.getBoolean(key, false)
        prefs.edit {
            putBoolean(key, enabled)
        }
        LyricPrefs.notifyFloatingStyleChanged()
        syncTileState(enabled)
    }

    private fun syncTileState(forceEnabled: Boolean? = null) {
        val tile = qsTile ?: return
        val prefs = LyricPrefs.floatingBasicStylePrefs
        val enabled = forceEnabled ?: prefs.getBoolean(key, false)
        tile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }
}
