/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.xposed.systemui.hook

import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.widget.TextView
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.proify.lyricon.lyric.style.BasicStyle
import io.github.proify.lyricon.xposed.logger.YLog
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 兼容策略:全局 `TextView#setTextColor` + 状态栏聚合
 *
 * 框架层 hook 保证全 ROM 生效:钩住 `TextView#setTextColor` 两个重载,
 * 但只收集**已绑定状态栏根视图**子树内 TextView 的颜色(排除歌词子树,
 * 避免歌词自身文字颜色污染),聚合取主色:
 * 绑定的时钟视图优先,否则取全部 TextView 的多数颜色。
 * 兼容模式下才存在该全局 hook,离开时通过 [XposedInterface.HookHandle] 撤销。
 *
 * @author Proify, Tomakino
 * @since 2026
 */
class CompatSetTextColorStrategy : StatusColorStrategy {

    companion object {
        private const val TAG = "CompatStrategy"

        /** 歌词视图容器 TAG(与 StatusBarLyric.VIEW_TAG 保持一致),用于排除歌词子树 */
        private const val LYRIC_VIEW_TAG = "lyricon:lyric_view"
    }

    override val id: Int = BasicStyle.STATUS_COLOR_STRATEGY_COMPAT
    override val name: String = "SetTextColor"

    private var emit: ((Int) -> Unit)? = null

    /** 全局 hook 句柄(存在即为已安装) */
    private val hookHandles = CopyOnWriteArrayList<XposedInterface.HookHandle>()

    /** 状态栏内 TextView 的最近颜色(弱引用键) */
    private val viewColors = WeakHashMap<View, Int>()

    /** 状态栏根视图的弱引用 */
    private var statusBarRoot: WeakReference<ViewGroup>? = null

    /** 时钟视图的弱引用(聚合时的优先参考视图) */
    private var clockView: WeakReference<View>? = null

    override fun onActivate(
        module: XposedModule,
        classLoader: ClassLoader,
        emit: (Int) -> Unit
    ) {
        this.emit = emit
        installHooks(module, classLoader)
    }

    override fun onDeactivate() {
        hookHandles.forEach { runCatching { it.unhook() } }
        hookHandles.clear()
        viewColors.clear()
        emit = null
        YLog.info(TAG, "Hooks removed")
    }

    override fun onBindStatusBar(root: ViewGroup?) {
        statusBarRoot = root?.let { WeakReference(it) }
        viewColors.clear()

        if (root != null) {
            collect(root)
            reportMainColor()
        }
    }

    override fun onBindClockView(view: View?) {
        clockView = view?.let { WeakReference(it) }

        if (view != null) {
            val color = (view as? TextView)?.currentTextColor?.takeIf { it != 0 }
            if (color != null) viewColors[view] = color
        }

        reportMainColor()
    }

    override fun onRefresh() {
        reportMainColor()
    }

    /**
     * 安装全局 hook:`TextView#setTextColor` 两个重载
     */
    private fun installHooks(module: XposedModule, classLoader: ClassLoader) {
        if (hookHandles.isNotEmpty()) return

        try {
            val classTextView = classLoader.loadClass("android.widget.TextView")
            val methods = arrayOf(
                classTextView.getDeclaredMethod(
                    "setTextColor",
                    ColorStateList::class.java
                ),
                classTextView.getDeclaredMethod(
                    "setTextColor",
                    Int::class.javaPrimitiveType
                )
            )

            @Suppress("ObjectLiteralToLambda")
            methods.forEach { method ->
                hookHandles.add(
                    module.hook(method).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            chain.proceed()
                            try {
                                val tv = chain.thisObject as? TextView ?: return null
                                afterSetColor(tv)
                            } catch (t: Throwable) {
                                YLog.error(TAG, "Callback failed", t)
                            }
                            return null
                        }
                    })
                )
            }
            YLog.info(TAG, "Compat hook installed (TextView#setTextColor)")
        } catch (t: Throwable) {
            YLog.error(TAG, "Failed to install hooks", t)
        }
    }

    /**
     * 处理颜色设置后的逻辑
     *
     * @param tv 发生颜色变化的 TextView 实例
     */
    private fun afterSetColor(tv: TextView) {
        val root = statusBarRoot?.get() ?: return
        if (tv.rootView !== root || isInLyricSubtree(tv)) return

        val color = tv.currentTextColor
        if (color == 0) return

        viewColors[tv] = color
        reportMainColor()
    }

    /**
     * 聚合状态栏 TextView 的主色并上报
     */
    private fun reportMainColor() {
        val emit = this.emit ?: return

        val color = computeMainColor() ?: return
        emit(color)
    }

    /**
     * 计算状态栏文字主色
     *
     * 优先级:时钟视图当前文字颜色 → 状态栏内全部 TextView 的多数颜色。
     *
     * @return 主色,无任何数据时返回 null
     */
    private fun computeMainColor(): Int? {
        val counts = HashMap<Int, Int>()
        val iterator = viewColors.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key == null) {
                iterator.remove()
            } else {
                val color = entry.value
                counts[color] = (counts[color] ?: 0) + 1
            }
        }

        (clockView?.get() as? TextView)
            ?.currentTextColor
            ?.takeIf { it != 0 }
            ?.let { return it }

        return counts.maxByOrNull { it.value }?.key
    }

    /**
     * 扫描状态栏根视图,收集所有 TextView 的当前文字颜色
     *
     * @param root 状态栏根视图
     */
    private fun collect(root: ViewGroup) {
        fun visit(view: View) {
            if (view is TextView && !isInLyricSubtree(view)) {
                val color = view.currentTextColor
                if (color != 0) {
                    viewColors[view] = color
                }
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    view.getChildAt(i)?.let { visit(it) }
                }
            }
        }
        visit(root)
    }

    /**
     * 判断视图是否位于歌词子树内
     *
     * @param view 目标视图
     * @return 是否在歌词子树内
     */
    private fun isInLyricSubtree(view: View): Boolean {
        var parent: ViewParent? = view.parent
        while (parent is View) {
            if (parent.tag == LYRIC_VIEW_TAG) return true
            parent = parent.parent
        }
        return false
    }
}
