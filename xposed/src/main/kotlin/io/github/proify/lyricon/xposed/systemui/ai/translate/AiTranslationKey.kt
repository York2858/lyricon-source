/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.xposed.systemui.ai.translate

import io.github.proify.android.extensions.md5
import io.github.proify.lyricon.lyric.ai.core.AiConfig
import io.github.proify.lyricon.lyric.ai.translate.isUsableLyricTranslation
import io.github.proify.lyricon.lyric.model.Song
import java.util.Locale

/**
 * 整首歌级翻译缓存 key 生成器。
 *
 * key 必须覆盖影响译文的所有输入：服务商 / Base URL / 模型 / 目标语言 /
 * 风格要求 / 歌曲元数据 / 逐行原文——任何一项变更都会生成新的 key，
 * 避免命中其他配置下的陈旧译文。
 */
internal object AiTranslationKey {

    fun calculate(
        configs: AiConfig,
        options: AiTranslationOptions,
        song: Song,
        lines: List<String>
    ): String {
        return buildString {
            // Old caches may contain echoed originals. Do not reuse them after validation changes.
            appendLine("policy=2")
            append("baseUrl=").appendLine(configs.baseUrl.orEmpty())
            append("model=").appendLine(configs.model.orEmpty())
            append("target=").appendLine(options.targetLanguage?.takeIf { it.isNotBlank() } ?: Locale.getDefault().toLanguageTag())
            append("style=").appendLine(options.stylePrompt.orEmpty())
            append("title=").appendLine(song.name.orEmpty())
            append("artist=").appendLine(song.artist.orEmpty())
            append("requested=").appendLine(AiTranslationPolicy.requestItems(lines, options, song).joinToString(",") { it.index.toString() })
            // Source translations are also included in library exports, so changes must not reuse stale versions.
            song.lyrics.orEmpty().forEachIndexed { index, line ->
                if (isUsableLyricTranslation(line.text, line.translation))
                    append("sourceTranslation=").append(index).append(':').appendLine(line.translation.orEmpty().trim())
            }
            lines.forEachIndexed { index, line ->
                append(index).append(':').appendLine(line)
            }
        }.md5()
    }
}
