/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.statusbarlyric

import io.github.proify.lyricon.lyric.style.RainbowTextColor

/** 整行彩虹配色；正文、未播放底色与高亮分别允许用户覆盖。 */
internal object RainbowPalette {
    fun resolve(lightMode: Boolean, custom: RainbowTextColor?): RainbowTextColor {
        val normal = if (lightMode) {
            intArrayOf(
                0xFFC53A3D.toInt(), 0xFFC06A00.toInt(), 0xFFB28800.toInt(),
                0xFF1E8E3E.toInt(), 0xFF1565C0.toInt(), 0xFF3949AB.toInt(),
                0xFF7B1FA2.toInt()
            )
        } else {
            intArrayOf(
                0xFFFF4D4F.toInt(), 0xFFFF9F43.toInt(), 0xFFFFD93D.toInt(),
                0xFF2ED573.toInt(), 0xFF1E90FF.toInt(), 0xFF5352ED.toInt(),
                0xFFA55EEA.toInt()
            )
        }
        val bgAlpha = ((if (lightMode) 0.26f else 0.45f) * 255).toInt()
        // 自定义配色只有在提供多色时才视为「彩虹」；单色无法形成渐变，回落到内置七色
        val accent = custom?.normal?.takeIf { it.size > 1 } ?: normal
        return RainbowTextColor(
            normal = accent,
            background = custom?.background?.takeIf { it.size > 1 }
                ?: accent.map { (it and 0x00FFFFFF) or (bgAlpha shl 24) }.toIntArray(),
            highlight = custom?.highlight?.takeIf { it.size > 1 } ?: accent
        )
    }
}
