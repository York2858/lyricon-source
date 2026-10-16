/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.lyric.model

import kotlin.text.isWhitespace

/**
 * 将逐字歌词的单词序列拼接为展示用文本。
 *
 * 当整行只包含拉丁字母而不含 CJK 字符时，会在词与词之间补空格，
 * 以避免罗马音/拉丁文歌词挤在一起；其余情况直接拼接。
 *
 * @param fallback 单词序列为空时的回退文本
 */
internal fun List<LyricWord>?.toDisplayText(fallback: String?): String? {
    if (this.isNullOrEmpty()) return fallback
    val joined = joinToString(separator = "") { it.text ?: "" }
    if (joined.isBlank()) return fallback ?: joined

    val hasLatin = joined.any { it in 'a'..'z' || it in 'A'..'Z' }
    val hasCjk = joined.any { char ->
        val code = char.code
        code in 12352 until 12544 ||
                code in 13312 until 19904 ||
                code in 19968 until 40960 ||
                code in 4352 until 4608 ||
                code in 12592 until 12688 ||
                code in 44032 until 55216
    }

    if (hasLatin && !hasCjk) {
        val words = this
        return buildString {
            words.forEach { word ->
                val text = word.text ?: ""
                if (text.isEmpty()) return@forEach
                if (isNotEmpty() &&
                    !last().isWhitespace() &&
                    !text.first().isWhitespace()
                ) {
                    append(' ')
                }
                append(text)
            }
        }.trim()
    }
    return joined
}
