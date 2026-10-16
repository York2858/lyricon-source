/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.colorextractor.palette

import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 纯 JVM 色彩数学工具(不依赖 Android 框架,可 JVM 单测)。
 *
 * 提供:
 * - sRGB ↔ OKLab ↔ OKLCh(Björn Ottosson 模型):色相/色度感知更均匀,用于质量分、色系和谐与背景适配
 * - sRGB ↔ CIELAB(D65):用于聚类空间与 Lab 距离
 * - WCAG 相对亮度与对比度
 */
internal object ColorMath {

    // ---------- sRGB 基础 ----------

    private fun channelOf(color: Int, shift: Int): Float = ((color shr shift) and 0xFF) / 255f

    private fun srgbToLinear(c: Float): Float =
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)

    private fun linearToSrgb(c: Float): Float =
        if (c <= 0.0031308f) c * 12.92f else 1.055f * c.pow(1.0f / 2.4f) - 0.055f

    private fun toArgb(r: Float, g: Float, b: Float): Int {
        val ri = (255f * linearToSrgb(r.coerceIn(0f, 1f))).roundToInt().coerceIn(0, 255)
        val gi = (255f * linearToSrgb(g.coerceIn(0f, 1f))).roundToInt().coerceIn(0, 255)
        val bi = (255f * linearToSrgb(b.coerceIn(0f, 1f))).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
    }

    // ---------- OKLab / OKLCh ----------

    /**
     * sRGB → OKLCh。
     *
     * @return [L(0..1), C(>=0), H(0..360)];色彩度趋于 0 时 H 取 0
     */
    fun okLCh(color: Int): FloatArray {
        val r = srgbToLinear(channelOf(color, 16))
        val g = srgbToLinear(channelOf(color, 8))
        val b = srgbToLinear(channelOf(color, 0))

        val l = 0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b
        val m = 0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b
        val s = 0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b

        val l3 = cbrt(l.toDouble()).toFloat()
        val m3 = cbrt(m.toDouble()).toFloat()
        val s3 = cbrt(s.toDouble()).toFloat()

        val L = 0.2104542553f * l3 + 0.7936177850f * m3 - 0.0040720468f * s3
        val a = 1.9779984951f * l3 - 2.4285922050f * m3 + 0.4505937099f * s3
        val bb = 0.0259040371f * l3 + 0.7827717662f * m3 - 0.8086757660f * s3

        val c = sqrt(a * a + bb * bb)
        val h = if (c < 1e-5f) 0f else {
            val deg = Math.toDegrees(atan2(bb.toDouble(), a.toDouble())).toFloat()
            if (deg < 0f) deg + 360f else deg
        }
        return floatArrayOf(L, c, h)
    }

    /**
     * OKLCh → sRGB(越界通道被裁剪)。
     *
     * @param L 亮度 0..1
     * @param c 彩度 >= 0
     * @param h 色相 0..360
     */
    fun okLChToColor(L: Float, c: Float, h: Float): Int {
        val rad = Math.toRadians(h.toDouble())
        val a = c * cos(rad).toFloat()
        val b = c * sin(rad).toFloat()

        val l3 = L + 0.3963377774f * a + 0.2158037573f * b
        val m3 = L - 0.1055613458f * a - 0.0638541728f * b
        val s3 = L - 0.0894841775f * a - 1.2914855480f * b

        val l = l3 * l3 * l3
        val m = m3 * m3 * m3
        val s = s3 * s3 * s3

        val r = 4.0767416621f * l - 3.3077115913f * m + 0.2309699292f * s
        val g = -1.2684380046f * l + 2.6097574011f * m - 0.3413193965f * s
        val bb = -0.0041960863f * l - 0.7034186147f * m + 1.7076147010f * s

        return toArgb(r, g, bb)
    }

    // ---------- CIELAB (D65) ----------

    /** sRGB → CIELAB,@return [L(0..100), a, b] */
    fun lab(color: Int): FloatArray {
        val r = srgbToLinear(channelOf(color, 16))
        val g = srgbToLinear(channelOf(color, 8))
        val b = srgbToLinear(channelOf(color, 0))

        val x = 0.4124564f * r + 0.3575761f * g + 0.1804375f * b
        val y = 0.2126729f * r + 0.7151522f * g + 0.0721750f * b
        val z = 0.0193339f * r + 0.1191920f * g + 0.9503041f * b

        val fx = labF(x / 0.95047f)
        val fy = labF(y)
        val fz = labF(z / 1.08883f)

        return floatArrayOf(116f * fy - 16f, 500f * (fx - fy), 200f * (fy - fz))
    }

    /** CIELAB → sRGB */
    fun labToColor(L: Float, a: Float, b: Float): Int {
        val fy = (L + 16f) / 116f
        val fx = fy + a / 500f
        val fz = fy - b / 200f

        val xr = labFinv(fx)
        val yr = labFinv(fy)
        val zr = labFinv(fz)

        val x = 0.95047f * xr
        val y = yr
        val z = 1.08883f * zr

        val r = 3.2404542f * x - 1.5371385f * y - 0.4985314f * z
        val g = -0.9692660f * x + 1.8760108f * y + 0.0415560f * z
        val bb = 0.0556434f * x - 0.2040259f * y + 1.0572252f * z

        return toArgb(r, g, bb)
    }

    private fun labF(t: Float): Float =
        if (t > 0.008856f) cbrt(t.toDouble()).toFloat() else 7.787f * t + 16f / 116f

    private fun labFinv(t: Float): Float =
        if (t > 0.206893034f) t * t * t else (t - 16f / 116f) / 7.787f

    // ---------- WCAG ----------

    /** WCAG 相对亮度(线性 RGB) */
    fun relativeLuminance(color: Int): Float {
        val r = srgbToLinear(channelOf(color, 16))
        val g = srgbToLinear(channelOf(color, 8))
        val b = srgbToLinear(channelOf(color, 0))
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }

    /** WCAG 对比度(1..21),与通道顺序无关 */
    fun contrast(c1: Int, c2: Int): Float {
        val l1 = relativeLuminance(c1)
        val l2 = relativeLuminance(c2)
        val hi = max(l1, l2)
        val lo = min(l1, l2)
        return (hi + 0.05f) / (lo + 0.05f)
    }

    // ---------- 色相 ----------

    /** 两个色相角(度)的环上距离,返回 0..180。 */
    fun angularDistance(h1: Float, h2: Float): Float {
        val diff = kotlin.math.abs(h1 - h2)
        return min(diff, 360f - diff)
    }
}
