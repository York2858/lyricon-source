/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.app.util

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import io.github.proify.android.extensions.fromJson
import io.github.proify.android.extensions.getPrivateSharedPreferences
import io.github.proify.android.extensions.json
import io.github.proify.android.extensions.safeDecode
import io.github.proify.android.extensions.toJson
import io.github.proify.lyricon.app.LyriconApp
import io.github.proify.lyricon.app.bridge.AppBridge.LyricStylePrefs
import io.github.proify.lyricon.app.bridge.AppBridge.LyricStylePrefs.KEY_CONFIGURED_PACKAGES
import io.github.proify.lyricon.app.bridge.AppBridge.LyricStylePrefs.KEY_ENABLED_PACKAGES
import io.github.proify.lyricon.app.bridge.AppBridgeConstants
import io.github.proify.lyricon.app.bridge.LyriconBridge
import io.github.proify.lyricon.common.PackageNames
import io.github.proify.lyricon.lyric.style.VisibilityRule
import io.github.proify.lyricon.lyric.style.BasicStyle

object LyricPrefs {

    const val DEFAULT_PACKAGE_NAME: String = LyricStylePrefs.DEFAULT_PACKAGE_NAME

    /** 悬浮窗样式跨进程通知的去抖间隔 */
    private const val NOTIFY_DEBOUNCE_MS = 120L

    private val packageStyleManager: SharedPreferences =
        getSharedPreferences(LyricStylePrefs.PREF_NAME_PACKAGE_MANAGER)

    val basicStylePrefs: SharedPreferences
        get() = getSharedPreferences(LyricStylePrefs.PREF_NAME_BASE)

    /** 悬浮窗基础样式偏好 SharedPreferences */
    val floatingBasicStylePrefs: SharedPreferences
        get() = getSharedPreferences(LyricStylePrefs.PREF_NAME_FLOATING_BASE_STYLE)

    /** 悬浮窗包级样式偏好 SharedPreferences */
    val floatingPackageStylePrefs: SharedPreferences
        get() = getSharedPreferences(LyricStylePrefs.PREF_NAME_FLOATING_PACKAGE_STYLE)

    /** 获取指定名称的 SharedPreferences*/
    fun getSharedPreferences(name: String): SharedPreferences {
        val xposedService = LyriconApp.xposedService
        if (xposedService != null) {
            return xposedService.getRemotePreferences(name)
        }

        return LyriconApp.get().getPrivateSharedPreferences(name)
    }

    fun isLyricStylePrefName(name: String): Boolean {
        return name.startsWith(LyricStylePrefs.LYRIC_STYLE_PREF_NAME_PREIFY)
    }

    /**
     * 通知模块刷新悬浮窗歌词样式与显示模式。
     *
     * App 进程写入偏好不会触发 SystemUI 进程内的偏好变更监听（缺少跨进程通知），
     * 因此切换开关或修改悬浮窗配置后需要主动通过桥接通知模块重新加载并应用。
     */
    fun notifyFloatingStyleChanged() {
        LyriconBridge.with(LyriconApp.get())
            .to(PackageNames.SYSTEM_UI)
            .key(AppBridgeConstants.REQUEST_UPDATE_LYRIC_STYLE)
            .send()
    }

    fun notifyLyricModeChanged(floatingMode: Boolean? = null, lyricsEnabled: Boolean? = null) {
        LyriconBridge.with(LyriconApp.get())
            .to(PackageNames.SYSTEM_UI)
            // Older running module versions still understand this command during an APK update.
            .key(AppBridgeConstants.REQUEST_UPDATE_LYRIC_STYLE)
            .payload(Bundle().apply {
                putBoolean(AppBridgeConstants.EXTRA_LYRIC_MODE_ONLY, true)
                putBoolean(AppBridgeConstants.EXTRA_FLOATING_MODE, floatingMode ?:
                    floatingBasicStylePrefs.getBoolean(BasicStyle.KEY_FLOATING_MODE, false))
                putBoolean(AppBridgeConstants.EXTRA_LYRICS_ENABLED, lyricsEnabled ?:
                    basicStylePrefs.getBoolean(BasicStyle.KEY_LYRICS_ENABLED, true))
            })
            .send()
    }

    private val notifyHandler = Handler(Looper.getMainLooper())

    private val pendingStyleNotify = Runnable { notifyFloatingStyleChanged() }

    /**
     * 去抖通知模块刷新悬浮窗样式：页面内的连续改动（如拖动字重滑块）只发一次跨进程通知。
     */
    fun scheduleFloatingStyleNotify() {
        notifyHandler.removeCallbacks(pendingStyleNotify)
        notifyHandler.postDelayed(pendingStyleNotify, NOTIFY_DEBOUNCE_MS)
    }

    /** 取消尚未执行的去抖通知（退出页面时先取消，再立即通知一次） */
    fun cancelScheduledFloatingStyleNotify() {
        notifyHandler.removeCallbacks(pendingStyleNotify)
    }

    /**
     * 悬浮窗配置页的偏好变更监听：注册后页面内改动即可实时下发，无需退出页面。
     */
    val floatingStyleChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == BasicStyle.KEY_FLOATING_MODE) notifyLyricModeChanged()
            else scheduleFloatingStyleNotify()
        }

    fun getLyricStylePrefNames() = mutableListOf<String>().apply {
        add(LyricStylePrefs.PREF_NAME_BASE)
        add(LyricStylePrefs.PREF_NAME_PACKAGE_MANAGER)

        getConfiguredPackageNames().forEach {
            add(getPackagePrefName(it))
        }
    }

    fun getPackagePrefName(packageName: String): String =
        LyricStylePrefs.getPackageStylePrefName(packageName)

    fun setEnabledPackageNames(names: Set<String>) {
        packageStyleManager.editCommit {
            putStringSet(KEY_ENABLED_PACKAGES, names)
        }
    }

    fun getEnabledPackageNames(): Set<String> {
        return packageStyleManager
            .getStringSet(KEY_ENABLED_PACKAGES, null)?.toSet() ?: emptySet()
    }

    fun setConfiguredPackageNames(names: Set<String>) {
        packageStyleManager.editCommit {
            putString(KEY_CONFIGURED_PACKAGES, names.toJson())
        }
    }

    fun getConfiguredPackageNames(): Set<String> {
        val jsonData = packageStyleManager.getString(KEY_CONFIGURED_PACKAGES, null)
        return json.safeDecode<List<String>>(jsonData).toSet()
    }

    fun setViewVisibilityRule(rules: List<VisibilityRule>?) {
        basicStylePrefs.editCommit {
            if (rules.isNullOrEmpty()) {
                remove("lyric_style_base_visibility_rules")
            } else {
                putString("lyric_style_base_visibility_rules", rules.toJson())
            }
        }
    }

    fun getViewVisibilityRule(): List<VisibilityRule> {
        val json = basicStylePrefs.getString("lyric_style_base_visibility_rules", null)
        return json?.fromJson<List<VisibilityRule>>() ?: emptyList()
    }
}
