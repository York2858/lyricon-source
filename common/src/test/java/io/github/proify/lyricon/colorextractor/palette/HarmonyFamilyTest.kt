/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.colorextractor.palette

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 色系族检测与和谐评分测试(纯 JVM)。
 */
class HarmonyFamilyTest {

    private fun detect(hues: FloatArray, chromas: FloatArray, mode: HarmonyMode = HarmonyMode.AUTO): HarmonyContext {
        val w = FloatArray(hues.size) { 1f }
        return HarmonyFamilyDetector.detect(hues, chromas, w, mode)
    }

    @Test
    fun `single hue detects MONO`() {
        val hues = FloatArray(200) { 20f + (it % 5) * 4f }          // 20..36°
        val ctx = detect(hues, FloatArray(200) { 0.2f })
        assertEquals(HarmonyFamily.MONO, ctx.family)
        assertTrue(ColorMath.angularDistance(ctx.anchorHue, 28f) < 15f)
    }

    @Test
    fun `red and cyan detects COMPLEMENT`() {
        val hues = FloatArray(200) { if (it < 100) 25f else 205f }   // 25° 与 205° 互补
        val ctx = detect(hues, FloatArray(200) { 0.22f })
        assertEquals(HarmonyFamily.COMPLEMENT, ctx.family)
    }

    @Test
    fun `spread adjacent hues detects ANALOGOUS`() {
        val hues = FloatArray(300) { (it % 80).toFloat() }           // 0..80°
        val ctx = detect(hues, FloatArray(300) { 0.2f })
        assertEquals(HarmonyFamily.ANALOGOUS, ctx.family)
    }

    @Test
    fun `gray image detects ACHROMATIC`() {
        val hues = FloatArray(100) { (it * 7 % 360).toFloat() }
        val ctx = detect(hues, FloatArray(100) { 0.02f })
        assertEquals(HarmonyFamily.ACHROMATIC, ctx.family)
    }

    @Test
    fun `four spread hues detects MIXED`() {
        val hues = FloatArray(400) { i ->
            when (i / 100) {
                0 -> 30f; 1 -> 120f; 2 -> 220f; else -> 300f
            }
        }
        val ctx = detect(hues, FloatArray(400) { 0.2f })
        assertEquals(HarmonyFamily.MIXED, ctx.family)
    }

    @Test
    fun `MONO family scores family hues high and outside hues low`() {
        val ctx = HarmonyContext(HarmonyFamily.MONO, anchorHue = 30f, hueConstrained = true)
        assertTrue(HarmonyFamilyDetector.harmonyScore(35f, 0.2f, ctx) == 1.0f)
        assertTrue(HarmonyFamilyDetector.harmonyScore(60f, 0.2f, ctx) > 0.5f)
        assertTrue(HarmonyFamilyDetector.harmonyScore(200f, 0.2f, ctx) < 0.2f)
    }

    @Test
    fun `MIXED family penalizes clash zone pairs`() {
        val ctx = HarmonyContext(HarmonyFamily.MIXED, anchorHue = 0f, hueConstrained = true)
        // 红(25°)与绿(145°):差 120°,落于冲突区(115..170)
        assertEquals(0.15f, HarmonyFamilyDetector.pairClashPenalty(25f, 145f, 0.2f, 0.2f, ctx), 1e-6f)
        // 互补对 25°/205°:不冲突
        assertEquals(1.0f, HarmonyFamilyDetector.pairClashPenalty(25f, 205f, 0.2f, 0.2f, ctx), 1e-6f)
        // 低彩度:不冲突
        assertEquals(1.0f, HarmonyFamilyDetector.pairClashPenalty(25f, 145f, 0.1f, 0.1f, ctx), 1e-6f)
    }

    @Test
    fun `MONO family does not double-penalize pair clash`() {
        val ctx = HarmonyContext(HarmonyFamily.MONO, anchorHue = 25f, hueConstrained = true)
        assertEquals(1.0f, HarmonyFamilyDetector.pairClashPenalty(25f, 145f, 0.2f, 0.2f, ctx), 1e-6f)
    }

    @Test
    fun `tier penalty only for cross-layer vivid colors`() {
        assertEquals(0.7f, HarmonyFamilyDetector.tierPenalty(0.25f, 0.1f), 1e-6f)
        assertEquals(1.0f, HarmonyFamilyDetector.tierPenalty(0.25f, 0.22f), 1e-6f)
        assertEquals(1.0f, HarmonyFamilyDetector.tierPenalty(0.2f, 0.05f), 1e-6f)
    }
}
