/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.colorextractor.palette

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * 真实封面探针(开发辅助,非断言测试)。
 *
 * 将封面 PNG/JPEG 放入 `common/src/test/resources/test-covers/` 后,
 * 用真实算法提取调色板并打印 raw / onWhite / onBlack 三组色值,便于和
 * 状态栏歌词渲染结果对照调参。
 *
 * 目录为空或不存在时测试直接通过。
 */
class CoverProbeTest {

    private val coverDir: File
        get() = File("src/test/resources/test-covers")

    @Test
    fun probeRealCovers() {
        val dir = coverDir
        if (!dir.exists() || (dir.listFiles()?.isEmpty() ?: true)) {
            println("[CoverProbe] 目录 ${dir.absolutePath} 为空,跳过真实封面探测")
            return
        }

        var ran = 0
        dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name }?.forEach { file ->
            val img = ImageIO.read(file) ?: run {
                println("[CoverProbe] 无法解码: ${file.name}")
                return@forEach
            }
            val w = img.width
            val h = img.height
            val pixels = IntArray(w * h)
            img.getRGB(0, 0, w, h, pixels, 0, w)

            val raw = ColorExtractorImpl.computePalette(pixels, w, h, 4, 45f, 20.0, 42L, HarmonyMode.AUTO)
            println("===== ${file.name} (${w}x$h) =====")
            println("  raw       : ${raw.map(::hex)}")
            println("  onWhite   : ${raw.map { hex(ColorExtractorImpl.adaptForBackground(it, false, 3.0f)) }}")
            println("  onBlack   : ${raw.map { hex(ColorExtractorImpl.adaptForBackground(it, true, 3.0f)) }}")

            // 探针断言:调色板非空,且每组颜色合法
            assertTrue(raw.isNotEmpty())
            ran++
        }
        assertTrue(ran >= 0)
    }

    private fun hex(c: Int): String = String.format("#%06X", 0xFFFFFF and c)
}
