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
import io.github.proify.lyricon.app.bridge.AppBridgeConstants
import io.github.proify.lyricon.colorextractor.palette.ColorExtractor
import io.github.proify.lyricon.colorextractor.palette.ColorPaletteResult
import io.github.proify.lyricon.common.PackageNames
import io.github.proify.lyricon.subscriber.util.ScreenStateMonitor
import io.github.proify.lyricon.lyric.style.BasicStyle
import io.github.proify.lyricon.lyric.style.LyricStyle
import io.github.proify.lyricon.lyric.style.TextStyle
import io.github.proify.lyricon.statusbarlyric.StatusBarLyric
import io.github.proify.lyricon.statusbarlyric.StatusColor
import io.github.proify.lyricon.xposed.logger.YLog
import io.github.proify.lyricon.xposed.systemui.lyric.control.LyricControlPopup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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
    private var controlHandle: FloatingLyricHandle? = null
    private var controlAttached = false

    private val lyricView: StatusBarLyric?
        get() {
            val context = appContext ?: return null
            val style = currentLyricStyle ?: return null
            _lyricView?.let { return it }
            return StatusBarLyric(context, style, null).apply {
                isDisabledVisible = true
                isSleepMode = ScreenStateMonitor.state == ScreenStateMonitor.ScreenState.OFF
                // 悬浮窗图标不跟随流体云胶囊隐藏
                logoView.capsuleHideEnabled = false
                addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                    syncControlWindow()
                }
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

        val view = lyricView ?: return
        view.forceAutoWidth = true
        view.updateStyle(lyricStyle)
        // 翻译行显示受悬浮窗自身样式控制，样式热更新后立即应用
        LyricViewController.applyTranslationVisibility(view, lyricStyle.packageStyle.text)
        applyContentAlignment(view, lyricStyle)

        val lp = layoutParams ?: return
        lp.width = resolveFloatingWindowWidth(lyricStyle)
        lp.flags = WINDOW_FLAGS
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

    /**
     * 在「状态栏歌词」与「悬浮窗歌词」之间切换显示模式。
     *
     * 拉起 App 侧既有的无界面切换入口，由 App 写回偏好并回传通知，
     * 保证 App 设置页状态与模块实际显示模式一致。
     */
    fun toggleLyricDisplayMode() {
        val context = appContext ?: return
        runCatching {
            context.sendBroadcast(
                Intent(AppBridgeConstants.ACTION_TOGGLE_FLOATING_LYRIC)
                    .setClassName(PackageNames.APPLICATION, AppBridgeConstants.LYRIC_GESTURE_RECEIVER)
            )
            YLog.info(TAG, "Requested lyric display mode toggle")
        }.onFailure {
            YLog.error(TAG, "Failed to toggle lyric display mode", it)
        }
    }

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

    private val coverScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var coverJob: Job? = null

    fun updateCoverThemeColors(coverFile: File?) {
        coverJob?.cancel()
        coverColorPaletteResult = null
        applyResolvedStatusColor()
        val style = currentLyricStyle ?: return
        if (!shouldUseCoverTextColor(style)) return
        coverJob = coverScope.launch {
            coverColorPaletteResult = ColorExtractor.extract(coverFile)
            applyResolvedStatusColor()
        }
    }

    private fun syncVisibilityByMode() {
        val view = lyricView ?: return
        // 悬浮窗开启时显示；关闭时交由状态栏歌词显示
        view.isDisabledVisible = !floatingLyricEnabled
        view.updateVisibility()
        syncControlWindow()
    }

    private fun syncControlWindow() {
        val context = appContext ?: return
        val wm = windowManager ?: return
        val style = currentLyricStyle ?: return
        val logo = _lyricView?.logoView
        val show = floatingLyricEnabled && style.basicStyle.floatingControlEnabled && LyricPrefs.gestureEnabled &&
                ScreenStateMonitor.state != ScreenStateMonitor.ScreenState.OFF &&
                _lyricView?.isHideOnLockScreen() == false &&
                style.packageStyle.logo.enable && logo != null && logo.width > 0 && logo.height > 0
        if (!show) {
            controlHandle?.let { handle ->
                LyricControlPopup.dismissIfOwnedBy(handle)
                if (controlAttached) {
                    runCatching { wm.removeViewImmediate(handle) }
                        .onFailure { YLog.error(TAG, "Remove floating control failed", it) }
                    controlAttached = false
                }
            }
            return
        }
        val handle = controlHandle ?: FloatingLyricHandle(
            context,
            configurationChanged = { syncControlWindow() }
        ).also { controlHandle = it }
        // 透明触摸层完全跟随悬浮歌词图标的位置与大小
        val location = IntArray(2)
        logo.getLocationOnScreen(location)
        val screenWidth = context.resources.displayMetrics.widthPixels
        val screenHeight = context.resources.displayMetrics.heightPixels
        val touchWidth = maxOf(logo.width, 48.dp)
        val touchHeight = maxOf(logo.height, 48.dp)
        val params = WindowManager.LayoutParams().apply {
            type = WINDOW_TYPE_APPLICATION_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            width = touchWidth
            height = touchHeight
            gravity = Gravity.TOP or Gravity.LEFT
            x = (location[0] - (touchWidth - logo.width) / 2)
                .coerceIn(0, (screenWidth - touchWidth).coerceAtLeast(0))
            y = (location[1] - (touchHeight - logo.height) / 2)
                .coerceIn(0, (screenHeight - touchHeight).coerceAtLeast(0))
            title = "Lyricon floating control"
        }
        runCatching {
            if (controlAttached) {
                val previous = handle.layoutParams as? WindowManager.LayoutParams
                if (previous?.x != params.x || previous.y != params.y ||
                    previous.width != params.width || previous.height != params.height) {
                    wm.updateViewLayout(handle, params)
                }
            }
            else {
                wm.addView(handle, params)
                controlAttached = true
            }
        }.onFailure { YLog.error(TAG, "Update floating control failed", it) }
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
        // 仅「已播放」配色跟随封面；未播放配色保持系统配色（亮色黑/暗色白），保证两种主题下都清晰
        if (shouldUseCoverTextGradient(style)) {
            val gradient = themeColors.swatches
            if (gradient.isNotEmpty()) statusColor.color = gradient
        } else {
            statusColor.color = intArrayOf(themeColors.primary)
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
            flags = WINDOW_FLAGS
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

}
