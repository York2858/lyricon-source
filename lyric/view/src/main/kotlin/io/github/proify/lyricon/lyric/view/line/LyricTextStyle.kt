/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.lyric.view.line

import android.graphics.Color
import android.text.TextPaint

/**
 * 正文/背景/高亮配色（单一来源），并负责把配色应用到正文 [TextPaint]。
 *
 * 与渲染引擎（[TextDrawer]）分离：本类只关心「配置 → Paint」，
 * 彩虹渐变缓存统一由 [RainbowGradientCache] 承担。
 */
internal class LyricTextStyle {

    private var primary = intArrayOf()
    var background = intArrayOf()
        private set
    var highlight = intArrayOf()
        private set

    private val rainbow = RainbowGradientCache()

    /** 记录当前配色；[lineWidth] 变化时 [applyTo] 会重建彩虹渐变。 */
    fun configure(primary: IntArray, background: IntArray, highlight: IntArray) {
        this.primary = primary
        this.background = background
        this.highlight = highlight
    }

    /** 将正文配色应用到 [paint]：单色直接着色，多色使用彩虹渐变。 */
    fun applyTo(paint: TextPaint, lineWidth: Float) {
        if (primary.isEmpty()) {
            paint.color = Color.BLACK
            paint.shader = null
        } else {
            paint.color = primary.firstOrNull() ?: Color.BLACK
            paint.shader = if (primary.size > 1) {
                rainbow.getOrCreate(lineWidth, primary)
            } else {
                null
            }
        }
    }

    /** 配色或行宽变化后的缓存刷新（例如切歌 / 换字体）。 */
    fun clearShaderCache() {
        rainbow.clear()
    }
}
