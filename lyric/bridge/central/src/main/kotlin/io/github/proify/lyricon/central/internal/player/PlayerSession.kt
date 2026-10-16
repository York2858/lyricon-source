/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.central.internal.player

import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.ProviderInfo

/**
 * 单个提供端的播放会话状态（可变）。
 *
 * 由 [PlayerCommandStub] 随命令更新；[ActivePlayerHub] 依据其状态事件
 * 决定活跃播放器。内容字段（歌曲/文本）互斥，由 [lyricType] 标记。
 *
 * @property providerInfo 所属提供端信息，用于身份比较与报告。
 */
internal data class PlayerSession(val providerInfo: ProviderInfo) {

    /** 最近设置的歌曲；设置时 [lyricType] 切换为 SONG。 */
    @Volatile
    var song: Song? = null
        set(value) {
            field = value
            lyricType = LyricType.SONG
        }

    /** 当前是否播放中。 */
    @Volatile
    var isPlaying: Boolean = false

    /** 最近已知的播放位置（毫秒），-1 表示未同步。 */
    @Volatile
    var position: Long = -1

    /** 最近推送的纯文本；设置时 [lyricType] 切换为 TEXT。 */
    @Volatile
    var text: String? = null
        set(value) {
            field = value
            lyricType = LyricType.TEXT
        }

    /** 是否建议显示翻译。 */
    @Volatile
    var isDisplayTranslation: Boolean = false

    /** 是否建议显示罗马音。 */
    @Volatile
    var isDisplayRoma = false

    /** 当前歌词内容类型。 */
    @Volatile
    var lyricType: LyricType = LyricType.NONE
        private set

    /** 歌词内容类型。 */
    enum class LyricType {
        /** 未设置内容。 */
        NONE,

        /** 结构化歌曲。 */
        SONG,

        /** 纯文本。 */
        TEXT
    }
}
