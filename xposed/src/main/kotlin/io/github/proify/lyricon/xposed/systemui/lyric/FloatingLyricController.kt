/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.xposed.systemui.lyric

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import io.github.proify.android.extensions.dp
import io.github.proify.android.extensions.setColorAlpha
import io.github.proify.android.extensions.toBitmap
import io.github.proify.lyricon.app.bridge.AppBridgeConstants
import io.github.proify.lyricon.colorextractor.palette.ColorExtractor
import io.github.proify.lyricon.colorextractor.palette.ColorPaletteResult
import io.github.proify.lyricon.common.PackageNames
import io.github.proify.lyricon.common.util.ScreenStateMonitor
import io.github.proify.lyricon.lyric.style.BasicStyle
import io.github.proify.lyricon.lyric.style.LyricStyle
import io.github.proify.lyricon.lyric.style.TextStyle
import io.github.proify.lyricon.statusbarlyric.StatusBarLyric
import io.github.proify.lyricon.statusbarlyric.StatusColor
import io.github.proify.lyricon.xposed.logger.YLog
import java.io.File

/**
 * 悬浮窗歌词控制器。
 *
 * 在 SystemUI 进程内使用 [WindowManager] 叠加一个独立歌词窗口，
 * 与状态栏歌词互斥显示，并跟随屏幕开关状态与封面取色变化。
 */
object FloatingLyricController : ScreenStateMonitor.ScreenStateListener {

    private const val TAG = "FloatingLyricController"
    private const val DEFAULT_FLOATING_WINDOW_WIDTH_DP = 360

    private var appContext: Context? = null
    private var windowManager: WindowManager? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var currentLyricStyle: LyricStyle? = null

    private var windowAttached = false
    private var floatingLyricEnabled = false

    private var sourceStatusColor: StatusColor = StatusColor()
    private var coverColorPaletteResult: ColorPaletteResult? = null

    private var _lyricView: StatusBarLyric? = null

    /** 双击切换显示模式：是否启用 */
    private var doubleTapSwitchMode = false

    private val lyricView: StatusBarLyric?
        get() {
            val context = appContext ?: return null
            val style = currentLyricStyle ?: return null
            _lyricView?.let { return it }
            return StatusBarLyric(context, style, null).apply {
                isDisabledVisible = true
                gestureListener = { gesture -> onLyricGesture(gesture) }
                _lyricView = this
            }
        }

    fun initialize(context: Context, lyricStyle: LyricStyle) {
        val appCtx = context.applicationContext
        appContext = appCtx
        currentLyricStyle = lyricStyle
        if (windowManager == null) {
            windowManager = appCtx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        }
        if (layoutParams == null) {
            layoutParams = buildLayoutParams(lyricStyle)
        }
        if (!windowAttached) {
            attachWindowIfNeeded()
            ScreenStateMonitor.addListener(this)
        }
        updateLyricStyle(lyricStyle)
    }

    fun lyricViewOrNull(): StatusBarLyric? = lyricView

    fun updateLyricStyle(lyricStyle: LyricStyle) {
        currentLyricStyle = lyricStyle
        floatingLyricEnabled = lyricStyle.basicStyle.manualFloatingLyricEnabled
        doubleTapSwitchMode = lyricStyle.basicStyle.floatingDoubleTapSwitchClock

        val view = lyricView ?: return
        view.forceAutoWidth = true
        view.updateStyle(lyricStyle)
        applyContentAlignment(view, lyricStyle)

        val lp = layoutParams ?: return
        lp.width = resolveFloatingWindowWidth(lyricStyle)
        lp.flags = resolveWindowFlags()
        applyLayoutPosition(lp, lyricStyle)

        if (windowAttached) {
            runCatching {
                windowManager?.updateViewLayout(view, lp)
            }.onFailure {
                YLog.error(TAG, "updateViewLayout failed", it)
                windowAttached = false
                attachWindowIfNeeded()
            }
        }

        if (shouldUseCoverTextColor(lyricStyle)) {
            updateCoverThemeColors(view.logoView.coverFile)
        } else {
            coverColorPaletteResult = null
            applyResolvedStatusColor()
        }

        syncVisibilityByMode()
    }

    // --- 双击切换显示模式 ---

    /**
     * 悬浮窗歌词手势回调。
     *
     * 双击切换由 [StatusBarLyric] 统一判定后以 [StatusBarLyric.GestureType.DOUBLE_TAP] 派发，
     * 这里只处理双击，避免与单击来源叠加导致一次双击切换两次。
     */
    private fun onLyricGesture(gesture: StatusBarLyric.GestureType) {
        if (!doubleTapSwitchMode) return
        if (gesture == StatusBarLyric.GestureType.DOUBLE_TAP) {
            toggleLyricDisplayMode()
        }
    }

    /**
     * 在「状态栏歌词」与「悬浮窗歌词」之间切换显示模式。
     *
     * 拉起 App 侧既有的无界面切换入口，由 App 写回偏好并回传通知，
     * 保证 App 设置页状态与模块实际显示模式一致。
     */
    fun toggleLyricDisplayMode() {
        val context = appContext ?: return
        runCatching {
            context.startActivity(
                Intent(AppBridgeConstants.ACTION_TOGGLE_FLOATING_LYRIC)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .setPackage(PackageNames.APPLICATION)
            )
            YLog.info(TAG, "Requested lyric display mode toggle")
        }.onFailure {
            YLog.error(TAG, "Failed to toggle lyric display mode", it)
        }
    }

    /** 窗口 flags；开启双击切换时需要接收触摸事件 */
    private fun resolveWindowFlags(): Int =
        if (doubleTapSwitchMode) WINDOW_FLAGS_TOUCHABLE else WINDOW_FLAGS

    fun onFloatingLyricModeChanged(enabled: Boolean) {
        floatingLyricEnabled = enabled
        syncVisibilityByMode()
    }

    fun setStatusColor(statusColor: StatusColor) {
        sourceStatusColor = statusColor.copy(
            color = statusColor.color.clone(),
            translucentColor = statusColor.translucentColor.clone()
        )
        applyResolvedStatusColor()
    }

    fun updateCoverThemeColors(coverFile: File?) {
        val style = currentLyricStyle
        coverColorPaletteResult = null
        if (style == null || !shouldUseCoverTextColor(style)) {
            applyResolvedStatusColor()
            return
        }
        val bitmap = coverFile?.toBitmap()
        if (bitmap == null) {
            applyResolvedStatusColor()
            return
        }
        try {
            ColorExtractor.extractAsync(
                bitmap = bitmap,
                cacheKey = { coverFile.name }
            ) { result ->
                coverColorPaletteResult = result
                applyResolvedStatusColor()
                bitmap.recycle()
            }
        } catch (e: Exception) {
            YLog.error(TAG, "Failed to extract cover theme colors", e)
            bitmap.recycle()
            applyResolvedStatusColor()
        }
    }

    private fun syncVisibilityByMode() {
        val view = lyricView ?: return
        // 悬浮窗开启时显示；关闭时交由状态栏歌词显示
        view.isDisabledVisible = !floatingLyricEnabled
        view.updateVisibility()
    }

    private fun shouldUseCoverTextColor(style: LyricStyle): Boolean {
        val textStyle: TextStyle = style.packageStyle.text
        return textStyle.enableExtractCoverTextColor && !textStyle.enableCustomTextColor
    }

    private fun shouldUseCoverTextGradient(style: LyricStyle): Boolean {
        return shouldUseCoverTextColor(style) && style.packageStyle.text.enableExtractCoverTextGradient
    }

    private fun applyResolvedStatusColor() {
        val view = lyricView ?: return
        val style = currentLyricStyle
        val statusColor = sourceStatusColor.copy(
            color = sourceStatusColor.color.clone(),
            translucentColor = sourceStatusColor.translucentColor.clone()
        )

        // 供「副行应用封面取色」使用
        val coverResultForSecondary = coverColorPaletteResult
        val coverScheme = if (coverResultForSecondary != null && style != null) {
            val themeColors = if (statusColor.isLightMode) {
                coverResultForSecondary.lightModeColors
            } else {
                coverResultForSecondary.darkModeColors
            }
            if (style.packageStyle.text.enableExtractCoverTextGradient) {
                themeColors.swatches
            } else {
                intArrayOf(themeColors.primary)
            }
        } else {
            null
        }
        view.textView.setCoverColorScheme(coverScheme)

        val coverResult = coverColorPaletteResult
        val useCoverColor = style != null && shouldUseCoverTextColor(style)
        if (!useCoverColor || coverResult == null) {
            view.setStatusBarColor(statusColor)
            return
        }

        val themeColors =
            if (statusColor.isLightMode) coverResult.lightModeColors else coverResult.darkModeColors
        if (shouldUseCoverTextGradient(style)) {
            val gradient = themeColors.swatches
            if (gradient.isNotEmpty()) {
                statusColor.color = gradient
                statusColor.translucentColor =
                    gradient.map { it.setColorAlpha(0.75f) }.toIntArray()
            }
        } else {
            val primary = themeColors.primary
            statusColor.color = intArrayOf(primary)
            statusColor.translucentColor = intArrayOf(primary.setColorAlpha(0.75f))
        }
        view.setStatusBarColor(statusColor)
    }

    private fun attachWindowIfNeeded() {
        val wm = windowManager ?: return
        val view = lyricView ?: return
        if (windowAttached) return

        val lp = layoutParams ?: buildLayoutParams(currentLyricStyle ?: return).also {
            layoutParams = it
        }

        runCatching {
            if (view.parent is ViewGroup) {
                (view.parent as ViewGroup).removeView(view)
            }
            wm.addView(view, lp)
            windowAttached = true
        }.onFailure {
            YLog.error(TAG, "add floating lyric window failed", it)
            windowAttached = false
        }
    }

    private fun buildLayoutParams(style: LyricStyle): WindowManager.LayoutParams {
        return WindowManager.LayoutParams().apply {
            type = WINDOW_TYPE_APPLICATION_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = resolveWindowFlags()
            width = resolveFloatingWindowWidth(style)
            height = WindowManager.LayoutParams.WRAP_CONTENT
            applyLayoutPosition(this, style)
        }
    }

    private fun applyLayoutPosition(lp: WindowManager.LayoutParams, style: LyricStyle) {
        lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        lp.x = 0
        lp.y = computeWindowOffsetY(style)
    }

    private fun applyContentAlignment(view: StatusBarLyric, style: LyricStyle) {
        val centerAligned = style.basicStyle.floatingLyricAlign != BasicStyle.FLOATING_ALIGN_LEFT
        val gravity = if (centerAligned) {
            Gravity.CENTER
        } else {
            Gravity.START or Gravity.CENTER_VERTICAL
        }
        view.gravity = gravity
        view.textView.gravity = gravity
        // 仅设置容器 gravity 不会改变行内文本的水平绘制原点，需同步强制居中
        view.textView.forceCenterContent = centerAligned
    }

    private fun computeWindowOffsetY(style: LyricStyle): Int {
        val context = appContext ?: return 0
        val statusBarHeightId =
            context.resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusBarHeight =
            if (statusBarHeightId != 0) context.resources.getDimensionPixelSize(statusBarHeightId) else 0
        return style.basicStyle.margins.top.dp + statusBarHeight
    }

    private fun resolveFloatingWindowWidth(style: LyricStyle): Int {
        val widthDp = style.basicStyle.floatingWidth
        return if (widthDp > 0f) widthDp.dp else DEFAULT_FLOATING_WINDOW_WIDTH_DP.dp
    }

    override fun onScreenOn() {
        lyricView?.isSleepMode = false
        syncVisibilityByMode()
    }

    override fun onScreenOff() {
        lyricView?.isSleepMode = true
        syncVisibilityByMode()
    }

    override fun onScreenUnlocked() {
        lyricView?.isSleepMode = false
        syncVisibilityByMode()
    }

    /** `WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY` */
    private const val WINDOW_TYPE_APPLICATION_OVERLAY = 2038

    /**
     * FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE | FLAG_NOT_TOUCH_MODAL | FLAG_LAYOUT_IN_SCREEN
     */
    private const val WINDOW_FLAGS = 312

    /**
     * 去掉 FLAG_NOT_TOUCHABLE，用于需要响应双击的「时钟/歌词」切换。
     */
    private const val WINDOW_FLAGS_TOUCHABLE = 296
}
