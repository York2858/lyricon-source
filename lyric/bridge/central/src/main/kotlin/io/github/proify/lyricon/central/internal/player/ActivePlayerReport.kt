/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.central.internal.player

import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.ProviderInfo

/**
 * 活跃播放器的一次完整状态快照。
 *
 * 活跃播放器切换或新监听器加入时，以该快照全量同步一次状态。
 */
internal data class ActivePlayerReport(
    val providerInfo: ProviderInfo,
    val isPlaying: Boolean,
    val song: Song?,
    val text: String?,
    val lyricType: PlayerSession.LyricType,
    val isDisplayTranslation: Boolean,
    val isDisplayRoma: Boolean,
    val position: Long
)
