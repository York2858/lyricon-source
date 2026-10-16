/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.lyric.view.line

import android.graphics.LinearGradient
import android.graphics.Shader

/**
 * 彩虹渐变缓存：同一 (宽度, 配色) 组合复用同一个 [LinearGradient]。
 *
 * 供正文着色（textPaint）与逐词着色（背景/高亮 [TextDrawer]）共用，
 * 避免两处各自维护一套彩虹 shader 缓存。
 */
internal class RainbowGradientCache {
    private var cached: LinearGradient? = null
    private var lastWidth = -1f
    private var lastColorsHash = 0

    /** 获取与 (width, colors) 匹配的彩虹渐变；宽度或配色变化时重建。 */
    fun getOrCreate(width: Float, colors: IntArray): LinearGradient {
        val colorsHash = colors.contentHashCode()
        if (cached == null || lastWidth != width || lastColorsHash != colorsHash) {
            cached = LinearGradient(
                0f, 0f, width, 0f,
                colors, null, Shader.TileMode.CLAMP
            )
            lastWidth = width
            lastColorsHash = colorsHash
        }
        return cached!!
    }

    fun clear() {
        cached = null
        lastWidth = -1f
        lastColorsHash = 0
    }
}
