/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.xposed.systemui.hook

import android.view.View
import android.view.ViewGroup
import io.github.libxposed.api.XposedModule

/**
 * 状态栏文字颜色同步策略
 *
 * 监控器 [StatusBarColorMonitor] 只负责:监听器管理、颜色结果去重上报、
 * 视图绑定与策略切换协调;具体的「如何感知与取色」由各策略实现。
 *
 * ### 扩展方式
 *
 * 未来新增方案(如 HyperOS 专项适配、暗色调度器通道等)只需:
 * 1. 实现本接口(提供唯一 [id],与 [StatusBarColorMonitor] 注册表对应);
 * 2. 在 [StatusBarColorMonitor] 初始化时通过 `register(...)` 注册;
 * 3. 在 [BasicStyle] 中补充策略 ID 常量并更新设置页选项。
 *
 * 策略生命周期(全部在主线程):
 * - [onActivate]:切换到该策略 / 监控器初始化时调用,可安装 hook;
 * - [onDeactivate]:离开该策略时调用,必须撤销本策略安装的全部 hook;
 * - [onBindStatusBar] / [onBindClockView]:视图生命周期通知(切换策略后会重放);
 * - [onRefresh]:布局等外部刷新时机。
 *
 * @author Proify, Tomakino
 * @since 2026
 */
interface StatusColorStrategy {

    /** 持久化的策略 ID(与 [BasicStyle.statusColorStrategy] 对应) */
    val id: Int

    /** 策略名称(用于日志) */
    val name: String

    /**
     * 激活本策略
     *
     * @param module Xposed 模块实例
     * @param classLoader 目标类加载器
     * @param emit 颜色上报回调(内部已去重,直接调用即可)
     */
    fun onActivate(
        module: XposedModule,
        classLoader: ClassLoader,
        emit: (Int) -> Unit
    )

    /**
     * 停用本策略,必须撤销 [onActivate] 安装的全部 hook
     */
    fun onDeactivate()

    /**
     * 状态栏根视图绑定通知
     *
     * @param root 状态栏根视图;传 null 表示清除绑定
     */
    fun onBindStatusBar(root: ViewGroup?)

    /**
     * 时钟视图绑定通知
     *
     * @param view 时钟视图;传 null 表示清除绑定
     */
    fun onBindClockView(view: View?)

    /**
     * 外部刷新时机(如状态栏布局事件)
     */
    fun onRefresh()
}
