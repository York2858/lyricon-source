/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.xposed.systemui.hook

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextClock
import android.widget.TextView
import io.github.proify.lyricon.xposed.logger.YLog
import io.github.proify.lyricon.xposed.systemui.hook.ClockViewFinder.SYSTEM_UI_PACKAGE

/**
 * 时钟视图发现工具
 *
 * 各 ROM 的状态栏时钟控件实现差异较大，可能不是默认的资源 ID（如 `clock`），
 * 因此按以下优先级进行匹配：
 * 1. 依次尝试常见候选资源 ID（`clock`、`status_bar_clock`、`clock_view` 等）；
 * 2. 在视图树中寻找 [TextClock] 实例（涵盖大部分 ROM 的时钟及其自定义子类）；
 * 3. 以类名关键词（如 `MiuiTextClock`、`OplusTextClock`、`HyperClock`）兜底匹配。
 *
 * 注意：时钟视图仅用于歌词视图的视觉对齐（字号同步），
 * 颜色监控请使用 [StatusBarColorMonitor]（跟随系统亮暗状态）。
 *
 * @author Proify, Tomakino
 * @since 2026
 */
object ClockViewFinder {

    private const val TAG = "ClockViewFinder"

    /** 候选时钟资源 ID 名称（按优先级排序），覆盖主流 ROM 的命名差异 */
    private val CLOCK_ID_NAMES = arrayOf(
        "clock",            // AOSP 及绝大多数 ROM（MIUI/HyperOS、ColorOS、OneUI、EMUI 等）
        "status_bar_clock", // 部分定制 ROM
        "clock_view",       // 部分定制 ROM
        "time",             // 部分 ROM
        "status_bar_time",  // 部分 ROM
    )

    /** 时钟类名关键词（不区分大小写），用于资源 ID 解析失败时的兜底匹配 */
    private val CLOCK_CLASS_KEYWORDS = arrayOf("clock", "time")

    /** 厂商 SystemUI 的包名候选，绝大多数 ROM 即使在深度定制下也保持该包名 */
    private const val SYSTEM_UI_PACKAGE = "com.android.systemui"

    /** 已解析出的时钟候选 ID 集合；null 表示尚未解析，空数组表示已解析但没有命中 */
    @Volatile
    private var resolvedClockIds: IntArray? = null

    /**
     * 在状态栏视图层级中发现时钟控件
     *
     * @param root 状态栏根视图
     * @return 时钟控件，未找到返回 null
     */
    @SuppressLint("DiscouragedApi")
    fun find(root: ViewGroup): View? {
        resolveClockIds(root.context)

        // 1. 候选 ID 匹配
        resolvedClockIds?.forEach { id ->
            root.findViewById<View>(id)?.let { return it }
        }

        // 2. TextClock 实例匹配（包含所有自定义子类）
        findFirst(root) { it is TextClock }?.let { return it }

        // 3. 类名关键词兜底匹配（覆盖非 TextClock 的自定义时钟实现）
        return findFirst(root) { matchClockByClassName(it) }
    }

    /**
     * 解析候选时钟资源 ID
     *
     * 优先使用根视图上下文所在包，失败时回退到 [SYSTEM_UI_PACKAGE]。
     * 结果会被缓存（包括无命中结果），避免重复解析。
     *
     * @param context 根视图上下文
     */
    @SuppressLint("DiscouragedApi")
    private fun resolveClockIds(context: Context) {
        resolvedClockIds?.let { return }

        val packages = LinkedHashSet<String>()
        runCatching { context.packageName }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { packages.add(it) }
        packages.add(SYSTEM_UI_PACKAGE)

        val ids = packages.flatMap { pkg ->
            CLOCK_ID_NAMES.mapNotNull { name ->
                runCatching { context.resources.getIdentifier(name, "id", pkg) }
                    .getOrDefault(0)
                    .takeIf { it != 0 }
            }
        }

        if (ids.isEmpty()) {
            YLog.warning(
                TAG,
                "No clock resource id found among ${CLOCK_ID_NAMES.contentToString()}"
            )
        }

        resolvedClockIds = ids.toIntArray()
    }

    /**
     * 深度优先遍历视图树，返回第一个满足条件的视图
     *
     * @param root 起始根视图
     * @param predicate 匹配条件
     * @return 匹配到的视图，未找到返回 null
     */
    private fun findFirst(root: ViewGroup, predicate: (View) -> Boolean): View? {
        val count = root.childCount
        for (i in 0 until count) {
            val child = root.getChildAt(i) ?: continue
            if (predicate(child)) return child
            if (child is ViewGroup) {
                findFirst(child, predicate)?.let { return it }
            }
        }
        return null
    }

    /**
     * 按类名关键词匹配时钟视图
     *
     * 覆盖各 ROM 的自定义时钟类（`MiuiTextClock`、`OplusTextClock`、`HyperClock`、
     * `SamsungStatusBarClock` 等），且限定为 [TextView] 子类，
     * 避免误匹配到非文本的容器视图。
     *
     * @param view 待匹配视图
     * @return 是否匹配
     */
    private fun matchClockByClassName(view: View): Boolean {
        if (view !is TextView) return false

        val className = view.javaClass.name
        return CLOCK_CLASS_KEYWORDS.any { keyword ->
            className.contains(keyword, ignoreCase = true)
        }
    }
}
