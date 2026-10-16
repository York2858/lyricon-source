/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.colorextractor.palette

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 调色板提取核心回归测试(纯 JVM,不依赖 Android 框架)。
 *
 * 覆盖 Phase 5 断言:
 * - 鲜艳主体 + 暗/白背景:不得混入脏色/背景中性色
 * - 同色系:输出为色阶渐变,色相集中,主色最艳
 * - 灰调图:不崩溃并返回合理数量
 * - 确定性:同 seed 输出稳定
 * - 背景适配:对比度达标且彩度保留
 */
class ColorExtractorCoreTest {

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun image(w: Int, h: Int, fill: (Int, Int) -> Int): IntArray {
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) px[y * w + x] = fill(x, y)
        return px
    }

    private fun inDisc(x: Int, y: Int, cx: Int, cy: Int, r: Int): Boolean {
        val dx = x - cx
        val dy = y - cy
        return dx * dx + dy * dy <= r * r
    }

    private fun Int.cOk(): Float = ColorMath.okLCh(this)[1]
    private fun Int.cL(): Float = ColorMath.okLCh(this)[0]
    private fun Int.cH(): Float = ColorMath.okLCh(this)[2]

    /** 是否属于"暗浊混合色"(鲜艳色与暗背景过渡产生的脏色) */
    private fun Int.isDarkMuted(): Boolean = cOk() < 0.16f && cL() < 0.55f

    private fun compute(
        px: IntArray,
        w: Int,
        h: Int,
        maxColors: Int = 4,
        seed: Long = 42L,
        mode: HarmonyMode = HarmonyMode.AUTO
    ): List<Int> =
        ColorExtractorImpl.computePalette(px, w, h, maxColors, 45f, 20.0, seed, mode)

    // ---------- Phase 5 核心回归:鲜艳色搭配脏色 ----------

    @Test
    fun `vividOnDarkBackground no dirty or dark muted color`() {
        val px = image(96, 96) { x, y ->
            when {
                inDisc(x, y, 48, 48, 30) -> argb(230, 30, 30)      // 鲜红主体
                x in 8..19 && y in 8..19 -> argb(40, 200, 90)      // 鲜绿点缀
                else -> argb(12, 12, 14)                           // 近黑背景
            }
        }
        val palette = compute(px, 96, 96)
        println("vividOnDark palette = ${palette.map(::hex)}")
        assertTrue("palette should not be empty", palette.isNotEmpty())
        assertTrue("expected at least 2 colors, got $palette", palette.size >= 2)
        for (c in palette) {
            assertFalse("dark muted color leaked into palette: #${hex(c)}", c.isDarkMuted())
            assertTrue("near-gray color leaked into palette: #${hex(c)}", c.cOk() >= 0.09f)
        }
    }

    @Test
    fun `whiteBackground suppressed when vivid present`() {
        val px = image(96, 96) { x, y ->
            if (inDisc(x, y, 48, 48, 32)) argb(255, 140, 0) else argb(245, 245, 244)
        }
        val palette = compute(px, 96, 96)
        println("whiteBg palette = ${palette.map(::hex)}")
        assertTrue(palette.isNotEmpty())
        assertTrue(palette.size <= 4)
        for (c in palette) {
            assertTrue("near-gray color leaked: #${hex(c)}", c.cOk() >= 0.12f)
        }
    }

    @Test
    fun `monochrome image yields tonal gradient with vivid primary`() {
        // 同一青色系、亮度阶梯的 5 段色带
        val bands = listOf(
            argb(5, 60, 70),
            argb(15, 100, 120),
            argb(30, 150, 175),
            argb(80, 200, 225),
            argb(160, 230, 245)
        )
        val px = image(100, 50) { x, _ -> bands[(x / 20).coerceIn(0, 4)] }
        val palette = compute(px, 100, 50)
        println("mono palette = ${palette.map(::hex)}")
        assertTrue("mono cover should yield >= 2 tonal colors, got $palette", palette.size >= 2)

        // 色相集中
        val mainHue = palette.first().cH()
        for (c in palette) {
            assertTrue(
                "hue drifted out of mono family: #${hex(c)}",
                ColorMath.angularDistance(c.cH(), mainHue) < 30f
            )
        }
        // 色阶清晰:任意两色要么明度差足够,要么彩度差足够
        for (i in palette.indices) {
            for (j in i + 1 until palette.size) {
                val a = ColorMath.okLCh(palette[i])
                val b = ColorMath.okLCh(palette[j])
                assertTrue(
                    "duplicate tone: #${hex(palette[i])} vs #${hex(palette[j])}",
                    kotlin.math.abs(a[0] - b[0]) >= 0.12f ||
                            kotlin.math.abs(a[1] - b[1]) >= 0.05f
                )
            }
        }
        // 主色(首位)为最艳
        val maxC = palette.maxOf { it.cOk() }
        assertEquals("primary should be the most vivid color", maxC, palette.first().cOk(), 1e-3f)
    }

    @Test
    fun `muted image does not crash and stays within bounds`() {
        val px = image(80, 80) { _, _ -> argb(110, 115, 128) }
        val palette = compute(px, 80, 80)
        println("muted palette = ${palette.map(::hex)}")
        assertTrue(palette.isNotEmpty())
        assertTrue(palette.size <= 4)
        assertTrue(px.size == 80 * 80)
    }

    @Test
    fun `multi-hue cover keeps at most one color per hue window`() {
        // 模拟图1式封面:青蓝、粉紫、黄橙、紫四条色带(等宽),不允许"两种紫"并存
        val bands = listOf(
            argb(60, 170, 220),  // 青蓝
            argb(225, 110, 190), // 粉紫
            argb(245, 175, 60),  // 黄橙
            argb(130, 70, 180)   // 紫
        )
        val px = image(100, 50) { x, _ -> bands[(x / 25).coerceIn(0, 3)] }
        val palette = compute(px, 100, 50)
        println("multiHue palette = ${palette.map(::hex)}")
        assertTrue(palette.isNotEmpty())
        for (i in palette.indices) {
            for (j in i + 1 until palette.size) {
                val a = ColorMath.okLCh(palette[i])
                val b = ColorMath.okLCh(palette[j])
                val sameHue = ColorMath.angularDistance(a[2], b[2]) < 45f
                val closeChroma = kotlin.math.abs(a[1] - b[1]) < 0.12f
                assertFalse(
                    "same-hue duplicate in multi-hue palette: " +
                            "#${hex(palette[i])} vs #${hex(palette[j])}",
                    sameHue && closeChroma
                )
            }
        }
    }

    @Test
    fun `result is deterministic for the same seed`() {
        val px = image(100, 60) { x, y ->
            if (inDisc(x, y, 50, 30, 22)) argb(210, 70, 160) else argb(28, 28, 30)
        }
        val a = compute(px, 100, 60, seed = 7L)
        val b = compute(px, 100, 60, seed = 7L)
        assertEquals(a, b)
    }

    // ---------- Phase 3:背景适配 ----------

    @Test
    fun `adapted colors meet contrast and keep chroma`() {
        val vivid = listOf(
            argb(230, 30, 30),   // 红
            argb(255, 140, 0),   // 橙
            argb(255, 220, 60),  // 黄
            argb(40, 200, 90),   // 绿
            argb(70, 70, 220),   // 蓝
            argb(160, 60, 210)   // 紫
        )
        val white = 0xFFFFFFFF.toInt()
        val black = 0xFF000000.toInt()

        for (c in vivid) {
            val light = ColorExtractorImpl.adaptForBackground(c, isDarkBg = false, 3.0f)
            assertTrue(
                "#${hex(light)} does not meet 3:1 on white (from #${hex(c)})",
                ColorMath.contrast(light, white) >= 3.0f
            )
            assertTrue(
                "chroma lost on light adapt: #${hex(c)} -> #${hex(light)}",
                light.cOk() >= 0.6f * c.cOk()
            )

            val dark = ColorExtractorImpl.adaptForBackground(c, isDarkBg = true, 3.0f)
            assertTrue(
                "#${hex(dark)} does not meet 3:1 on black (from #${hex(c)})",
                ColorMath.contrast(dark, black) >= 3.0f
            )
            assertTrue(
                "chroma lost on dark adapt: #${hex(c)} -> #${hex(dark)}",
                dark.cOk() >= 0.6f * c.cOk()
            )
        }
    }

    @Test
    fun `adapt extreme colors without infinite loop`() {
        val white = 0xFFFFFFFF.toInt()
        val black = 0xFF000000.toInt()
        // 黑/白/纯蓝(亮度上限最苛刻的颜色)
        for (c in listOf(black, white, argb(0, 0, 255))) {
            val light = ColorExtractorImpl.adaptForBackground(c, isDarkBg = false, 3.0f)
            val dark = ColorExtractorImpl.adaptForBackground(c, isDarkBg = true, 3.0f)
            assertTrue(ColorMath.contrast(light, white) >= 3.0f)
            assertTrue(ColorMath.contrast(dark, black) >= 3.0f)
        }
    }

    @Test
    fun `white background adaptation never exceeds gamut`() {
        val c = argb(120, 60, 200)
        val light = ColorExtractorImpl.adaptForBackground(c, isDarkBg = false, 4.5f)
        val dark = ColorExtractorImpl.adaptForBackground(c, isDarkBg = true, 4.5f)
        // 输出必须是合法不透明 RGB(注意用无符号右移,Int 为负数时有符号 shr 会扩展符号位)
        assertTrue(light ushr 24 == 0xFF)
        assertTrue(dark ushr 24 == 0xFF)
    }

    // ---------- 日志辅助 ----------

    private fun hex(c: Int): String = String.format("#%06X", 0xFFFFFF and c)
}
