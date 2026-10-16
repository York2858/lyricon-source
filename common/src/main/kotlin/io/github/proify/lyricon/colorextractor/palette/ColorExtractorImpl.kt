/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.colorextractor.palette

import android.graphics.Bitmap
import androidx.core.graphics.scale
import io.github.proify.lyricon.colorextractor.palette.ColorExtractorImpl.scoreCandidate
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 主色主导级颜色提取器(Theme-Adaptive · Harmony Edition)。
 *
 * 从位图中提取代表性颜色,并针对浅色/深色背景自动生成高对比度衍生色。
 * 核心流程:
 * 1. 缩放图片以限制最大采样像素数
 * 2. 转换至 Lab(聚类空间)与 OKLCh(质控/色系空间),以彩度² 赋予初始权重
 * 3. 建立"低彩度背景锚",抑制接近背景的过渡像素,突出主体鲜艳色
 * 4. 加权 K-means++ 聚类多次运行取最优;簇中心不取全簇均值,
 *    而是取簇内彩度前 ~30% 子集的加权均值(高色度代表色),避免鲜艳色
 *    与暗背景混合后变成"脏色"
 * 5. 质量分 × 多样性 × 色系和谐的贪心选择:
 *    - 质量分:OKLCH 彩度平滑映射,压制灰浊色
 *    - 多样性:Lab 距离 + 色相角差(允许同色系内亮度/彩度色阶)
 *    - 色系和谐:自动检测 MONO / ANALOGOUS / COMPLEMENT / MIXED / ACHROMATIC
 *      族,按族窗口约束候选色相,惩罚 115°..170° 高彩度冲突区与彩度跨层
 * 6. 筛选后处理:鲜艳色充足时剔除残留脏色,并按"锚点色相 → 最艳主色在前"
 *    编排渐变顺序
 * 7. 在 OKLCh 空间根据目标背景亮度调整 L(保持色相与彩度),并验证 WCAG
 *    对比度;亮度大幅调整时微升彩度以保持鲜艳感
 */
object ColorExtractorImpl {

    // ---------- 默认提取参数 ----------
    /** 未指定 [maxColors] 时的默认提取颜色数 */
    private const val DEFAULT_MAX_COLORS = 4
    /** 最大采样像素总数,用于控制性能 */
    private const val MAX_SAMPLE_PIXELS = 150 * 150

    // ---------- K-means 聚类常量 ----------
    /** K-means 迭代次数 */
    private const val KMEANS_ITERATIONS = 15
    /** K-means 聚类中心倍数:以 maxColors × 此倍数的中心数进行聚类,再合并筛选 */
    private const val KMEANS_MULTIPLIER = 3
    /** K-means 运行次数,取类内加权误差平方和最小的解 */
    private const val KMEANS_TRIALS = 3

    // ---------- 背景抑制(Phase 1b) ----------
    /** 参与背景锚估计的最大 Lab 彩度 */
    private const val BG_CHROMA_CUT = 15f
    /** 背景锚高斯抑制的方差项(2σ²),σ=20 Lab 单位 */
    private const val BG_ANCHOR_SIGMA_SQ_2 = 2f * 20f * 20f
    /** 完全位于背景上的像素权重保留比例 */
    private const val BG_SUPPRESS_MIN = 0.22f
    /** 背景像素数低于该占比时不做抑制(全图无低彩度区域) */
    private const val BG_MIN_FRACTION = 0.03f

    // ---------- 去重 / 同色系色阶(Phase 2.5d) ----------
    /** 默认色相阈值(度):高彩度颜色之间需大于此值才视为不同色;低于该值的同色相对按色阶规则判断 */
    private const val DEFAULT_HUE_THRESHOLD = 45f
    /** 默认 Lab 距离阈值:距离小于此值时收紧多样性 */
    private const val DEFAULT_DIST_THRESHOLD = 20.0
    /** MONO 族同色系色阶:允许的最小 OKLCH 亮度差 */
    private const val TONAL_L_STEP = 0.15f
    /** MONO 族同色系色阶:允许的最小 OKLCH 彩度差 */
    private const val TONAL_C_STEP = 0.08f
    /** 非 MONO 族同色相共存:需要同时满足的亮度差(明显不同色调才允许双色占位) */
    private const val STRONG_TONAL_L_STEP = 0.25f
    /** 非 MONO 族同色相共存:需要同时满足的彩度差 */
    private const val STRONG_TONAL_C_STEP = 0.12f

    // ---------- 质量分(Phase 2a) ----------
    /** 质量分平滑映射的低彩度端点 */
    private const val SIGNAL_CHROMA_LO = 0.06f
    /** 质量分平滑映射的高彩度端点 */
    private const val SIGNAL_CHROMA_HI = 0.26f
    /** 脏色判定:OKLCH 彩度低于该值(灰/褐/脏浊) */
    private const val DIRTY_CHROMA_OK = 0.085f
    /** 脏色判定:彩度低于该值且偏暗(鲜艳色与暗背景的混合色) */
    private const val DIRTY_DARK_CHROMA_OK = 0.16f
    /** 脏色判定:偏暗的亮度上限 */
    private const val DIRTY_DARK_L_OK = 0.55f
    /** 鲜艳色判定:OKLCH 彩度不低于该值 */
    private const val VIVID_CHROMA_OK = 0.15f
    /** 中性色判定:接近极亮 */
    private const val NEUTRAL_L_HI = 0.88f
    /** 中性色判定:接近极暗 */
    private const val NEUTRAL_L_LO = 0.12f
    /** 贪心选择的最低可接受得分,低于此值停止补选 */
    private const val MIN_SELECT_SCORE = 0.02f

    // ---------- 背景自适应(Phase 3) ----------
    /** 深色背景上推荐 OKLCH 明度的最低值 */
    private const val DARK_BG_LIGHTNESS_MIN = 0.70f
    /** 深色背景上推荐 OKLCH 明度的最高值 */
    private const val DARK_BG_LIGHTNESS_MAX = 0.86f
    /** 浅色背景上推荐 OKLCH 明度的最低值 */
    private const val LIGHT_BG_LIGHTNESS_MIN = 0.32f
    /** 浅色背景上推荐 OKLCH 明度的最高值 */
    private const val LIGHT_BG_LIGHTNESS_MAX = 0.48f
    /** 默认最小对比度(歌词大字号场景,WCAG AA Large 3:1;可参数覆盖) */
    private const val DEFAULT_MIN_CONTRAST_RATIO = 3.0f
    /** 对比度不足时明度调整步长 */
    private const val L_ADJUST_STEP = 0.02f
    /** 对比度满足前的最大尝试次数 */
    private const val MAX_ADJUST_ATTEMPTS = 12
    /** OKLCH 彩度输出上限(避免越界) */
    private const val MAX_OKLCH_CHROMA = 0.34f
    /** 明度大幅调整超过该值时启动彩度回补 */
    private const val CHROMA_BOOST_THRESHOLD = 0.12f
    /** 彩度回补比例 */
    private const val CHROMA_BOOST = 0.18f

    /** 聚类结果:高色度代表色(Lab) + 簇总权重 */
    private class ClusterRep(val l: Float, val a: Float, val b: Float, val weight: Float)

    /** 候选色:质量与色系信息 */
    private class Candidate(
        val color: Int,
        val weight: Float,
        val lOk: Float,
        val cOk: Float,
        val hOk: Float,
        val quality: Float,
        val dirty: Boolean,
        val vivid: Boolean,
        val neutral: Boolean
    )

    /**
     * 提取具备背景适配能力的主题调色板。
     *
     * @param bitmap 输入位图,不可为已回收状态
     * @param maxColors 期望提取的最大颜色数量
     * @param hueThreshold 色相去重阈值(度),值越大允许更相近的色相
     * @param distThreshold Lab 距离去重阈值,值越小保留越多相近颜色
     * @param seed 随机种子,用于固定聚类结果;为 null 时每次随机
     * @param harmonyMode 色系平衡模式,默认 [HarmonyMode.AUTO] 自动检测
     * @param minContrastRatio 适配色相对背景的最小 WCAG 对比度,默认 3.0(AA Large 大字号)
     * @return [ThemePalette] 包含原始代表色及深/浅背景适配色
     */
    fun extractThemePalette(
        bitmap: Bitmap,
        maxColors: Int = DEFAULT_MAX_COLORS,
        hueThreshold: Float = DEFAULT_HUE_THRESHOLD,
        distThreshold: Double = DEFAULT_DIST_THRESHOLD,
        seed: Long? = null,
        harmonyMode: HarmonyMode = HarmonyMode.AUTO,
        minContrastRatio: Float = DEFAULT_MIN_CONTRAST_RATIO
    ): ThemePalette {
        val raw = extract(bitmap, maxColors, hueThreshold, distThreshold, seed, harmonyMode)
        return ThemePalette(
            rawColors = raw,
            onWhiteBackground = raw.map { adaptForBackground(it, isDarkBg = false, minContrastRatio) },
            onBlackBackground = raw.map { adaptForBackground(it, isDarkBg = true, minContrastRatio) }
        )
    }

    /**
     * 核心颜色提取逻辑(Bitmap 入口),返回按重要性排序的代表色列表。
     *
     * @param bitmap 输入位图,不可为已回收状态
     * @param maxColors 期望提取的最大颜色数量
     * @param hueThreshold 色相去重阈值(度)
     * @param distThreshold Lab 距离去重阈值
     * @param seed 随机种子
     * @param harmonyMode 色系平衡模式
     * @return 代表色列表,可能少于 [maxColors] 但不会为空(除非输入全透明)
     */
    fun extract(
        bitmap: Bitmap,
        maxColors: Int = DEFAULT_MAX_COLORS,
        hueThreshold: Float = DEFAULT_HUE_THRESHOLD,
        distThreshold: Double = DEFAULT_DIST_THRESHOLD,
        seed: Long? = null,
        harmonyMode: HarmonyMode = HarmonyMode.AUTO
    ): List<Int> {
        require(!bitmap.isRecycled) { "Bitmap is already recycled" }

        val scaled = scaleBitmap(bitmap, MAX_SAMPLE_PIXELS)
        val width = scaled.width
        val height = scaled.height
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        if (scaled != bitmap) scaled.recycle()

        return computePalette(pixels, width, height, maxColors, hueThreshold, distThreshold, seed, harmonyMode)
    }

    /**
     * 纯 JVM 核心:像素数组 → 代表色列表。无 Android 依赖,可单测。
     */
    internal fun computePalette(
        pixels: IntArray,
        width: Int,
        height: Int,
        maxColors: Int,
        hueThreshold: Float,
        distThreshold: Double,
        seed: Long?,
        harmonyMode: HarmonyMode
    ): List<Int> {
        val size = if (pixels.size == width * height) pixels.size else minOf(pixels.size, width * height)
        if (size == 0) return emptyList()

        val lArr = FloatArray(size)
        val aArr = FloatArray(size)
        val bArr = FloatArray(size)
        val chrArr = FloatArray(size)
        val okCArr = FloatArray(size)
        val okHArr = FloatArray(size)
        val wArr = FloatArray(size)
        var totalW = 0f

        // 1. Lab(聚类空间)+ OKLCh(质控空间)+ 初始彩度权重
        for (i in 0 until size) {
            val lab = ColorMath.lab(pixels[i])
            val ok = ColorMath.okLCh(pixels[i])
            val chroma = sqrt(lab[1] * lab[1] + lab[2] * lab[2])

            lArr[i] = lab[0]; aArr[i] = lab[1]; bArr[i] = lab[2]
            chrArr[i] = chroma
            okCArr[i] = ok[1]; okHArr[i] = ok[2]

            // 鲜艳颜色权重高,低饱和颜色给予基础权重(且彩度变化被背景抑制单独处理)
            val weight = if (chroma > 5f) chroma * chroma else 0.1f
            wArr[i] = weight
            totalW += weight
        }
        if (totalW == 0f) return emptyList()

        // 2. 背景锚抑制:低彩度区域估计"背景色",靠近背景的像素降权
        var bgCount = 0
        var bgL = 0f; var bgA = 0f; var bgB = 0f
        for (i in 0 until size) {
            val ch = chrArr[i]
            if (ch < BG_CHROMA_CUT) {
                bgCount++
                val bw = (1f - ch / BG_CHROMA_CUT).coerceIn(0.2f, 1f)
                bgL += lArr[i] * bw
                bgA += aArr[i] * bw
                bgB += bArr[i] * bw
            }
        }
        if (bgCount >= size * BG_MIN_FRACTION) {
            val anchorL = bgL / bgCount
            val anchorA = bgA / bgCount
            val anchorB = bgB / bgCount
            for (i in 0 until size) {
                val dL = lArr[i] - anchorL
                val dA = aArr[i] - anchorA
                val dB = bArr[i] - anchorB
                val distSq = dL * dL + dA * dA + dB * dB
                // 靠近背景:权重保留 BG_SUPPRESS_MIN;远离背景:完全保留
                wArr[i] *= BG_SUPPRESS_MIN + (1f - BG_SUPPRESS_MIN) * (1f - exp(-distSq / BG_ANCHOR_SIGMA_SQ_2))
            }
        }

        // 3. 加权 K-means++ 聚类(每簇输出高色度代表色)
        val maxK = maxColors.coerceAtLeast(1) * KMEANS_MULTIPLIER
        val k = maxK.coerceAtMost(size).coerceAtLeast(1)
        val clusters = kMeansLabOptimized(lArr, aArr, bArr, wArr, chrArr, k, seed)
        if (clusters.isEmpty()) return emptyList()

        // 4. 构建候选:质量分 + 脏色/鲜艳色/中性色判定
        val candidates = clusters.map { rep ->
            val color = ColorMath.labToColor(rep.l, rep.a, rep.b)
            val ok = ColorMath.okLCh(color)
            val quality = 0.15f + 0.85f * smoothStep(ok[1], SIGNAL_CHROMA_LO, SIGNAL_CHROMA_HI)
            val dirty = ok[1] < DIRTY_CHROMA_OK ||
                    (ok[1] < DIRTY_DARK_CHROMA_OK && ok[0] < DIRTY_DARK_L_OK)
            val vivid = ok[1] >= VIVID_CHROMA_OK
            val neutral = dirty || ok[0] > NEUTRAL_L_HI || ok[0] < NEUTRAL_L_LO
            Candidate(color, rep.weight, ok[0], ok[1], ok[2], quality, dirty, vivid, neutral)
        }

        // 5. 色系族检测(基于像素级 OKLCh 分布)
        val ctx = HarmonyFamilyDetector.detect(okHArr, okCArr, wArr, harmonyMode)

        // 6. 质量×多样性×和谐 贪心选择
        val selected = selectCandidates(candidates, maxColors, hueThreshold, distThreshold, ctx)

        // 7. 筛选后处理:去脏 + 渐变编排
        return arrange(selected, maxColors, candidates, hueThreshold, distThreshold, ctx)
    }

    /**
     * 贪心选择:每次选取"质量 × 色系和谐 × 与已选多样性"得分最高的候选。
     */
    private fun selectCandidates(
        candidates: List<Candidate>,
        maxColors: Int,
        hueThreshold: Float,
        distThreshold: Double,
        ctx: HarmonyContext
    ): List<Candidate> {
        val selected = ArrayList<Candidate>()
        val remaining = candidates.toMutableList()
        val wMax = max(candidates.maxByOrNull { it.weight }?.weight ?: 0f, 1e-6f)

        while (selected.size < maxColors && remaining.isNotEmpty()) {
            val hasVivid = selected.any { it.vivid }
            var best: Candidate? = null
            var bestScore = Float.NEGATIVE_INFINITY
            for (c in remaining) {
                // 已有鲜艳色时,脏色(灰色/暗浊混合色)与纯中性色(黑/白背景)不入组
                if (hasVivid && c.dirty) continue
                if (hasVivid && c.neutral && !c.vivid) continue
                val score = scoreCandidate(c, selected, wMax, hueThreshold, distThreshold, ctx)
                if (score > bestScore) {
                    bestScore = score
                    best = c
                }
            }
            if (best == null || bestScore < MIN_SELECT_SCORE) break
            selected.add(best)
            remaining.remove(best)
        }

        // 兜底:全图灰调等极端情况,至少返回权重最高的候选
        if (selected.isEmpty() && candidates.isNotEmpty()) {
            selected.add(candidates[0])
        }
        return selected
    }

    /**
     * 候选得分 = 归一化权重^0.5 × 质量分 × 色系和谐分
     *            × 已选成对多样性(距离/同名色阶/冲突区/彩度分层)。
     */
    private fun scoreCandidate(
        c: Candidate,
        selected: List<Candidate>,
        wMax: Float,
        hueThreshold: Float,
        distThreshold: Double,
        ctx: HarmonyContext
    ): Float {
        val wNorm = if (wMax > 0f) sqrt((c.weight / wMax).coerceIn(0f, 1f)) else 1f
        val harmony = HarmonyFamilyDetector.harmonyScore(c.hOk, c.cOk, ctx)
        val base = max(wNorm * c.quality * harmony, 1e-4f)
        if (selected.isEmpty()) return base

        var pair = 1f
        for (s in selected) {
            val dLab = calculateLabDistance(c.color, s.color)
            val hueClose = ColorMath.angularDistance(c.hOk, s.hOk) < hueThreshold
            var p: Float
            if (hueClose) {
                // 同色相:仅 MONO 族允许普通色阶;多色族必须"明显不同色调"
                // (同时满足 ΔL 与 ΔC)才允许双色占位,否则视为近重复
                val tonalOk = if (ctx.family == HarmonyFamily.MONO) {
                    abs(c.lOk - s.lOk) >= TONAL_L_STEP || abs(c.cOk - s.cOk) >= TONAL_C_STEP
                } else {
                    abs(c.lOk - s.lOk) >= STRONG_TONAL_L_STEP && abs(c.cOk - s.cOk) >= STRONG_TONAL_C_STEP
                }
                p = if (tonalOk) 0.55f else 0.05f
            } else if (dLab < distThreshold.toFloat()) {
                // 色相不同但距离近(如两种灰调):收紧,避免近重复入组
                p = 0.55f
            } else {
                p = 0.35f + 0.65f * (min(dLab, 55f) / 55f)
            }
            p *= HarmonyFamilyDetector.pairClashPenalty(c.hOk, s.hOk, c.cOk, s.cOk, ctx)
            p *= HarmonyFamilyDetector.tierPenalty(c.cOk, s.cOk)
            pair = min(pair, p)
        }
        return base * pair
    }

    /**
     * 同色相窗口内的"近重复"判定:色调差不足时视为重复。
     * 与 [scoreCandidate] 的强色阶门控保持一致。
     */
    private fun isHueDuplicate(c: Candidate, others: List<Candidate>, hueThreshold: Float): Boolean {
        for (s in others) {
            if (ColorMath.angularDistance(c.hOk, s.hOk) >= hueThreshold) continue
            val tonalOk = abs(c.lOk - s.lOk) >= STRONG_TONAL_L_STEP &&
                    abs(c.cOk - s.cOk) >= STRONG_TONAL_C_STEP
            if (!tonalOk) return true
        }
        return false
    }

    /**
     * 筛选后处理(Phase 4):
     * 1. 鲜艳色 >= 2 时剔除残留脏色;
     * 2. 非 MONO 族清理同色相"近重复"(同色相窗口内仅保留彩度最高者,
     *    避免多色封面出现"两种紫"这类冗余);
     * 3. 从剩余候选中按质量回填;
     * 4. 编排:最艳色作为首位(primary),其余按"距上一位的 Lab 距离最近邻"
     *    贪心排序——相邻渐变色感知距离最小,色相连续不跳变;灰调图按明度降序。
     */
    private fun arrange(
        selected: List<Candidate>,
        maxColors: Int,
        allCandidates: List<Candidate>,
        hueThreshold: Float,
        distThreshold: Double,
        ctx: HarmonyContext
    ): List<Int> {
        var result = selected
        if (result.count { it.vivid } >= 2) {
            val cleaned = result.filter { !it.dirty }
            if (cleaned.isNotEmpty()) result = cleaned
        }

        // 同色相近重复清理(仅多色族;MONO 鼓励色阶,ACHROMATIC 无色相意义)
        if (ctx.family != HarmonyFamily.MONO && ctx.family != HarmonyFamily.ACHROMATIC) {
            val deduped = ArrayList<Candidate>(result.size)
            for (c in result.sortedByDescending { it.cOk }) {
                if (!isHueDuplicate(c, deduped, hueThreshold)) deduped.add(c)
            }
            if (deduped.isNotEmpty()) result = deduped
        }

        // 回填(同样遵守同色相门控,避免把刚清理的重复色加回来)
        if (result.size < maxColors) {
            val filled = result.toMutableList()
            val remaining = allCandidates.filter { c -> filled.none { it.color == c.color } }.toMutableList()
            val wMax = max(allCandidates.maxByOrNull { it.weight }?.weight ?: 0f, 1e-6f)
            while (filled.size < maxColors && remaining.isNotEmpty()) {
                val hasVivid = filled.any { it.vivid }
                var best: Candidate? = null
                var bestScore = Float.NEGATIVE_INFINITY
                for (c in remaining) {
                    if (hasVivid && c.dirty) continue
                    if (hasVivid && c.neutral && !c.vivid) continue
                    if (ctx.family != HarmonyFamily.MONO && ctx.family != HarmonyFamily.ACHROMATIC &&
                        isHueDuplicate(c, filled, hueThreshold)
                    ) continue
                    val score = scoreCandidate(c, filled, wMax, hueThreshold, distThreshold, ctx)
                    if (score > bestScore) {
                        bestScore = score
                        best = c
                    }
                }
                if (best == null || bestScore < MIN_SELECT_SCORE) break
                filled.add(best)
                remaining.remove(best)
            }
            result = filled
        }

        // 编排:最艳色作为 primary(同彩度时偏向锚点色相)
        if (result.isEmpty()) return emptyList()
        val primary = result.maxWithOrNull(
            compareBy({ it.cOk }, { ColorMath.angularDistance(it.hOk, ctx.anchorHue) })
        ) ?: result.first()
        val rest = result.filter { it !== primary }.toMutableList()
        val restOrd: List<Candidate> = if (ctx.family == HarmonyFamily.ACHROMATIC) {
            rest.sortedByDescending { it.lOk }
        } else {
            // 最近邻贪心(Lab 距离):相邻色感知距离最小,渐变融合顺滑
            val path = ArrayList<Candidate>(rest.size)
            var current = primary
            while (rest.isNotEmpty()) {
                val next = rest.minByOrNull { calculateLabDistance(it.color, current.color) }!!
                path.add(next)
                rest.remove(next)
                current = next
            }
            path
        }
        return (listOf(primary) + restOrd).map { it.color }
    }

    /**
     * 加权 K-means 聚类(CIELAB 空间),返回按簇总权重降序的"高色度代表色"。
     *
     * 与旧实现的关键差异:簇中心更新仍取加权均值,但**输出颜色**为簇内
     * 彩度前 ~30%(均值 + 0.5σ)子集的加权均值——避免鲜艳主体与暗背景
     * 混合后,代表色被过渡像素拉成暗浊混合色(脏色来源之一)。
     */
    private fun kMeansLabOptimized(
        lArr: FloatArray, aArr: FloatArray, bArr: FloatArray,
        wArr: FloatArray, chrArr: FloatArray, k: Int, seed: Long?
    ): List<ClusterRep> {
        if (k <= 0 || lArr.isEmpty()) return emptyList()

        val size = lArr.size
        var bestError = Double.MAX_VALUE
        var bestCenters: Array<FloatArray>? = null
        var bestAssignments: IntArray? = null

        val random = seed?.let { Random(it) } ?: Random

        repeat(KMEANS_TRIALS) {
            // --- K-means++ 初始化 ---
            val cL = FloatArray(k)
            val cA = FloatArray(k)
            val cB = FloatArray(k)

            val firstIdx = random.nextInt(size)
            cL[0] = lArr[firstIdx]
            cA[0] = aArr[firstIdx]
            cB[0] = bArr[firstIdx]

            val minDistSq = FloatArray(size) { Float.MAX_VALUE }
            for (ci in 1 until k) {
                var sumDistSq = 0.0
                for (i in 0 until size) {
                    val d = (lArr[i] - cL[ci - 1]).let { it * it } +
                            (aArr[i] - cA[ci - 1]).let { it * it } +
                            (bArr[i] - cB[ci - 1]).let { it * it }
                    if (d < minDistSq[i]) minDistSq[i] = d
                    sumDistSq += minDistSq[i].toDouble()
                }
                val threshold = random.nextDouble() * sumDistSq
                var cumulative = 0.0
                var nextIdx = 0
                for (i in 0 until size) {
                    cumulative += minDistSq[i]
                    if (cumulative >= threshold) {
                        nextIdx = i
                        break
                    }
                }
                cL[ci] = lArr[nextIdx]
                cA[ci] = aArr[nextIdx]
                cB[ci] = bArr[nextIdx]
            }

            val assignments = IntArray(size)

            // Lloyd 迭代
            repeat(KMEANS_ITERATIONS) {
                for (i in 0 until size) {
                    var minDist = Float.MAX_VALUE
                    var closest = 0
                    for (ci in 0 until k) {
                        val d = (lArr[i] - cL[ci]).let { it * it } +
                                (aArr[i] - cA[ci]).let { it * it } +
                                (bArr[i] - cB[ci]).let { it * it }
                        if (d < minDist) {
                            minDist = d
                            closest = ci
                        }
                    }
                    assignments[i] = closest
                }
                val nL = FloatArray(k)
                val nA = FloatArray(k)
                val nB = FloatArray(k)
                val nW = FloatArray(k)
                for (i in 0 until size) {
                    val ci = assignments[i]
                    val w = wArr[i]
                    nL[ci] += lArr[i] * w
                    nA[ci] += aArr[i] * w
                    nB[ci] += bArr[i] * w
                    nW[ci] += w
                }
                for (ci in 0 until k) {
                    if (nW[ci] > 0) {
                        cL[ci] = nL[ci] / nW[ci]
                        cA[ci] = nA[ci] / nW[ci]
                        cB[ci] = nB[ci] / nW[ci]
                    }
                }
            }

            // 计算加权误差平方和
            var error = 0.0
            for (i in 0 until size) {
                val ci = assignments[i]
                val d = (lArr[i] - cL[ci]).let { it * it } +
                        (aArr[i] - cA[ci]).let { it * it } +
                        (bArr[i] - cB[ci]).let { it * it }
                error += wArr[i] * d
            }

            if (error < bestError) {
                bestError = error
                bestCenters = Array(k) { i -> floatArrayOf(cL[i], cA[i], cB[i]) }
                bestAssignments = assignments.copyOf()
            }
        }

        val centers = bestCenters ?: return emptyList()
        val assign = bestAssignments ?: return emptyList()

        // --- 每簇统计:彩度均值/方差(高色度子集阈值)与全簇加权均值(兜底) ---
        val n = FloatArray(k)
        val sumC = FloatArray(k)
        val sumC2 = FloatArray(k)
        val sumL = FloatArray(k)
        val sumA = FloatArray(k)
        val sumB = FloatArray(k)
        val sumW = FloatArray(k)
        for (i in 0 until size) {
            val ci = assign[i]
            n[ci]++
            val ch = chrArr[i]
            sumC[ci] += ch
            sumC2[ci] += ch * ch
            val w = wArr[i]
            sumL[ci] += lArr[i] * w
            sumA[ci] += aArr[i] * w
            sumB[ci] += bArr[i] * w
            sumW[ci] += w
        }

        val thr = FloatArray(k)
        for (ci in 0 until k) {
            if (n[ci] <= 0f) continue
            val mean = sumC[ci] / n[ci]
            val variance = max(0f, sumC2[ci] / n[ci] - mean * mean)
            thr[ci] = mean + 0.5f * sqrt(variance)
        }

        val sL = FloatArray(k)
        val sA = FloatArray(k)
        val sB = FloatArray(k)
        val sW = FloatArray(k)
        for (i in 0 until size) {
            val ci = assign[i]
            if (n[ci] <= 0f) continue
            if (chrArr[i] >= thr[ci]) {
                val w = wArr[i]
                sL[ci] += lArr[i] * w
                sA[ci] += aArr[i] * w
                sB[ci] += bArr[i] * w
                sW[ci] += w
            }
        }

        val reps = ArrayList<ClusterRep>(k)
        for (ci in 0 until k) {
            if (n[ci] <= 0f) continue
            val useSubset = sW[ci] >= 0.35f * sumW[ci] && sW[ci] > 0f
            val rl: Float
            val ra: Float
            val rb: Float
            if (useSubset) {
                rl = sL[ci] / sW[ci]
                ra = sA[ci] / sW[ci]
                rb = sB[ci] / sW[ci]
            } else if (sumW[ci] > 0f) {
                rl = sumL[ci] / sumW[ci]
                ra = sumA[ci] / sumW[ci]
                rb = sumB[ci] / sumW[ci]
            } else {
                rl = centers[ci][0]
                ra = centers[ci][1]
                rb = centers[ci][2]
            }
            reps.add(ClusterRep(rl, ra, rb, sumW[ci]))
        }
        return reps.sortedByDescending { it.weight }
    }

    /**
     * 根据目标背景明暗,在 OKLCh 空间调整明度(保持色相与彩度),并强制满足
     * WCAG 对比度;明度大幅调整时微升彩度以保持鲜艳感。
     *
     * @param color 原始颜色
     * @param isDarkBg `true` 表示目标背景为深色,需要亮色前景
     * @param minContrastRatio 最小目标对比度
     * @return 调整后的颜色
     */
    internal fun adaptForBackground(
        color: Int,
        isDarkBg: Boolean,
        minContrastRatio: Float = DEFAULT_MIN_CONTRAST_RATIO
    ): Int {
        val ok = ColorMath.okLCh(color)
        var l = ok[0]
        val c0 = ok[1]
        val h = ok[2]

        val (targetMin, targetMax) = if (isDarkBg)
            DARK_BG_LIGHTNESS_MIN to DARK_BG_LIGHTNESS_MAX
        else
            LIGHT_BG_LIGHTNESS_MIN to LIGHT_BG_LIGHTNESS_MAX

        val originalL = l
        l = l.coerceIn(targetMin, targetMax)
        var result = ColorMath.okLChToColor(l, c0, h)

        val backgroundColor = if (isDarkBg) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        var attempts = 0
        while (ColorMath.contrast(result, backgroundColor) < minContrastRatio &&
            attempts < MAX_ADJUST_ATTEMPTS
        ) {
            val step = if (isDarkBg) L_ADJUST_STEP else -L_ADJUST_STEP
            val next = (l + step).coerceIn(0f, 1f)
            if (next == l) break
            l = next
            result = ColorMath.okLChToColor(l, c0, h)
            attempts++
        }

        // 保色度:明度大幅调整时回补少量彩度,避免"变闷"(对比度仍满足才应用)
        if (abs(l - originalL) >= CHROMA_BOOST_THRESHOLD) {
            val boosted = ColorMath.okLChToColor(l, min(c0 * (1f + CHROMA_BOOST), MAX_OKLCH_CHROMA), h)
            if (ColorMath.contrast(boosted, backgroundColor) >= minContrastRatio * 0.97f) {
                result = boosted
            }
        }

        return result
    }

    /** 计算两个 sRGB 颜色在 CIELAB 空间中的欧氏距离 */
    private fun calculateLabDistance(c1: Int, c2: Int): Float {
        val l1 = ColorMath.lab(c1)
        val l2 = ColorMath.lab(c2)
        return sqrt(
            (l1[0] - l2[0]).let { it * it } +
                    (l1[1] - l2[1]).let { it * it } +
                    (l1[2] - l2[2]).let { it * it }
        )
    }

    /** 平滑阶跃:0..1 单调映射 */
    private fun smoothStep(x: Float, lo: Float, hi: Float): Float {
        val t = ((x - lo) / (hi - lo)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * 缩放位图使其总像素数不超过 [maxPixels]。
     * 若原图已在限制内,直接返回原图。
     */
    private fun scaleBitmap(bitmap: Bitmap, maxPixels: Int): Bitmap {
        val totalPixels = bitmap.width * bitmap.height
        if (totalPixels <= maxPixels) return bitmap
        val scale = sqrt(maxPixels.toFloat() / totalPixels)
        return bitmap.scale(
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1)
        )
    }

    /**
     * 主题调色板结果集。
     *
     * @param rawColors 从图片直接提取的原始代表色列表
     * @param onWhiteBackground 适合显示在白色背景上的颜色(高对比度暗色)
     * @param onBlackBackground 适合显示在黑色背景上的颜色(高对比度亮色)
     */
    data class ThemePalette(
        val rawColors: List<Int>,
        val onWhiteBackground: List<Int>,
        val onBlackBackground: List<Int>
    )
}
