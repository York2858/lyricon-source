/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.colorextractor.palette

import io.github.proify.lyricon.colorextractor.palette.HarmonyFamilyDetector.SIGNAL_CHROMA
import io.github.proify.lyricon.colorextractor.palette.HarmonyFamilyDetector.harmonyScore
import kotlin.math.max

/**
 * 色系平衡模式。
 *
 * - [AUTO]:自动从图片色相分布检测色系族(默认)
 * - [MONO]/[ANALOGOUS]/[COMPLEMENT]/[MIXED]:强制按指定族约束
 * - [OFF]:关闭色系约束,仅保留质量+多样性选择(接近旧行为)
 */
enum class HarmonyMode { AUTO, MONO, ANALOGOUS, COMPLEMENT, MIXED, OFF }

/**
 * 检测到的色系族。
 *
 * - [ACHROMATIC]:图像几乎没有彩色信号(灰调),不做色相约束,按亮度编排
 * - [MONO]:单一色相 → 强制同色系,允许色阶(亮度/彩度阶梯)
 * - [ANALOGOUS]:相邻色相 → 限制在锚点色相 ±90° 内,冲突区惩罚
 * - [COMPLEMENT]:锚点色相 + 对侧互补峰 → 允许锚侧/对侧两个窗口
 * - [MIXED]:无明确结构 → 仅做冲突区与彩度分层约束
 */
enum class HarmonyFamily { ACHROMATIC, MONO, ANALOGOUS, COMPLEMENT, MIXED }

internal data class HarmonyContext(
    val family: HarmonyFamily,
    val anchorHue: Float,
    val hueConstrained: Boolean
)

/**
 * 色系族检测与和谐评分(纯 JVM)。
 *
 * 检测基于 OKLCh 色相的加权直方图(36 桶,每桶 10°),权重沿用像素权重
 * (色度² × 背景抑制),因此主导鲜艳色决定锚点色相。
 */
internal object HarmonyFamilyDetector {

    /** 参与色相直方图的像素最小 OKLCH 彩度 */
    private const val SIGNAL_CHROMA = 0.09f

    /** 彩色信号占全部权重比例低于该值时判定为 ACHROMATIC */
    private const val SIGNAL_FRACTION = 0.06f

    /** 锚点 ±22.5° 内质量占比超过该值判定为 MONO */
    private const val MONO_FRACTION = 0.55f

    /** 锚点 ±60° 内质量占比超过该值判定为 ANALOGOUS */
    private const val ANALOGOUS_FRACTION = 0.70f

    /** 对侧窗(锚点 +180°±30°)占比超过该值判定为 COMPLEMENT */
    private const val COMPLEMENT_FRACTION = 0.20f

    // ---------- 冲突区 ----------
    /** 冲突区色相跨度(度):两侧都是高彩度时视觉打架 */
    const val CLASH_HUE_LO = 115f
    const val CLASH_HUE_HI = 170f
    /** 参与冲突判定的最小 OKLCH 彩度 */
    const val CLASH_CHROMA = 0.17f

    // ---------- 彩度分层 ----------
    /** 两色都高于该彩度时,才进行彩度分层一致性约束 */
    const val TIER_CHROMA_MIN = 0.09f
    /** 两色彩度差超过该值视为跨层,惩罚 */
    const val TIER_CHROMA_GAP = 0.12f
    /** 彩度跨层惩罚系数 */
    const val TIER_PENALTY = 0.7f

    private const val BINS = 36

    /**
     * 从像素级 OKLCh 分布检测色系族。
     *
     * @param hues 像素 OKLCH C(仅彩度 >= [SIGNAL_CHROMA] 的像素参与)
     * @param chromas 像素 OKLCH 彩度
     * @param weights 像素权重
     * @param mode [HarmonyMode]
     */
    fun detect(
        hues: FloatArray,
        chromas: FloatArray,
        weights: FloatArray,
        mode: HarmonyMode
    ): HarmonyContext {
        val size = minOf(hues.size, chromas.size, weights.size)
        val bins = FloatArray(BINS)
        var totalW = 0f
        var signalW = 0f
        for (i in 0 until size) {
            val w = weights[i]
            totalW += w
            if (chromas[i] >= SIGNAL_CHROMA) {
                signalW += w
                bins[(hues[i] / 10f).toInt().coerceIn(0, BINS - 1)] += w
            }
        }

        var anchorBin = 0
        var anchorMax = 0f
        for (i in 0 until BINS) {
            if (bins[i] > anchorMax) {
                anchorMax = bins[i]
                anchorBin = i
            }
        }
        val anchorHue = (anchorBin * 10 + 5) % 360f
        val signalFraction = signalW / max(totalW, 1e-6f)

        val family: HarmonyFamily = when {
            mode == HarmonyMode.OFF -> HarmonyFamily.MIXED
            mode != HarmonyMode.AUTO -> when (mode) {
                HarmonyMode.MONO -> HarmonyFamily.MONO
                HarmonyMode.ANALOGOUS -> HarmonyFamily.ANALOGOUS
                HarmonyMode.COMPLEMENT -> HarmonyFamily.COMPLEMENT
                else -> HarmonyFamily.MIXED
            }
            signalFraction < SIGNAL_FRACTION -> HarmonyFamily.ACHROMATIC
            else -> {
                val frac22 = windowFraction(bins, anchorHue, 22.5f)
                val fracComp = windowFractionInRange(bins, anchorHue, 150f, 210f)
                when {
                    frac22 >= MONO_FRACTION -> HarmonyFamily.MONO
                    fracComp >= COMPLEMENT_FRACTION -> HarmonyFamily.COMPLEMENT
                    windowFraction(bins, anchorHue, 60f) >= ANALOGOUS_FRACTION -> HarmonyFamily.ANALOGOUS
                    else -> HarmonyFamily.MIXED
                }
            }
        }

        return HarmonyContext(family, anchorHue, hueConstrained = mode != HarmonyMode.OFF)
    }

    /** 候选色相与色系族的和谐分(0..1),仅针对"与锚点色相的关系"。 */
    fun harmonyScore(hue: Float, chroma: Float, ctx: HarmonyContext): Float {
        if (!ctx.hueConstrained) return 1.0f
        return when (ctx.family) {
            HarmonyFamily.MONO -> {
                val d = ColorMath.angularDistance(hue, ctx.anchorHue)
                when {
                    d <= 25f -> 1.0f
                    d <= 60f -> 1.0f - 0.5f * (d - 25f) / 35f
                    else -> 0.15f
                }
            }
            HarmonyFamily.ANALOGOUS -> {
                val d = ColorMath.angularDistance(hue, ctx.anchorHue)
                when {
                    d <= 30f -> 1.0f
                    d <= 90f -> 1.0f - 0.5f * (d - 30f) / 60f
                    d <= 115f -> 0.45f
                    d <= 165f -> 0.10f
                    d <= 195f -> 0.30f
                    else -> 0.45f
                }
            }
            HarmonyFamily.COMPLEMENT -> {
                val d = ColorMath.angularDistance(hue, ctx.anchorHue)
                when {
                    d <= 30f -> 1.0f
                    d <= 60f -> 0.8f
                    d <= 115f -> 0.5f
                    d < 150f -> 0.25f
                    d <= 210f -> 0.95f
                    else -> 0.5f
                }
            }
            else -> 1.0f // MIXED / ACHROMATIC:不做色相窗口约束
        }
    }

    /**
     * 成对冲突惩罚:两个高彩度候选色相落在 115°..170° 的"半互补冲突区"
     * 时,视觉上互相打架(如红/绿、橙/蓝而不互补),需强惩罚。
     *
     * 仅对 [HarmonyFamily.MIXED] 族执行——结构化族(MONO/ANALOGOUS/COMPLEMENT)
     * 的色相区间已由 [harmonyScore] 统一约束,若再叠加成对惩罚会造成双重扣分,
     * 误杀真实存在的彩度副色。
     */
    fun pairClashPenalty(h1: Float, h2: Float, c1: Float, c2: Float, ctx: HarmonyContext): Float {
        if (!ctx.hueConstrained) return 1.0f
        if (ctx.family != HarmonyFamily.MIXED) return 1.0f
        if (c1 < CLASH_CHROMA || c2 < CLASH_CHROMA) return 1.0f
        val d = ColorMath.angularDistance(h1, h2)
        return if (d >= CLASH_HUE_LO && d <= CLASH_HUE_HI) 0.15f else 1.0f
    }

    /**
     * 彩度分层一致性:两色都达到中等彩度且跨层过远时,视觉上出现
     * "一个极艳、一个灰浊"的断层,给予中等惩罚。
     */
    fun tierPenalty(c1: Float, c2: Float): Float {
        if (c1 < TIER_CHROMA_MIN || c2 < TIER_CHROMA_MIN) return 1.0f
        return if (kotlin.math.abs(c1 - c2) > TIER_CHROMA_GAP) TIER_PENALTY else 1.0f
    }

    /** 锚点色相 ±[window] 窗口内的加权质量占比(相对彩色信号总量)。 */
    private fun windowFraction(bins: FloatArray, anchorHue: Float, window: Float): Float =
        windowFractionInRange(bins, anchorHue, anchorHue - window, anchorHue + window)

    /** [from,to](度,自动环回)窗口内的加权质量占比。 */
    private fun windowFractionInRange(bins: FloatArray, anchorHue: Float, from: Float, to: Float): Float {
        var total = 0f
        var mass = 0f
        for (i in bins.indices) {
            val w = bins[i]
            total += w
            val binHue = (i * 10 + 5) % 360f
            if (isInWindow(binHue, from, to)) mass += w
        }
        if (total <= 0f) return 0f
        return mass / total
    }

    private fun isInWindow(hue: Float, from: Float, to: Float): Boolean {
        if (from <= to) return hue in from..to
        return hue >= from || hue <= to
    }
}
