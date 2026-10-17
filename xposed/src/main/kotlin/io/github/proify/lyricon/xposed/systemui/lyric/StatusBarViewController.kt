/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.xposed.systemui.lyric

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import androidx.core.graphics.toColorInt
import androidx.core.view.doOnAttach
import androidx.core.view.isVisible
import io.github.proify.android.extensions.dp
import io.github.proify.android.extensions.isLandScape
import io.github.proify.lyricon.colorextractor.palette.ColorExtractor
import io.github.proify.lyricon.colorextractor.palette.ColorPaletteResult
import io.github.proify.lyricon.common.util.ResourceMapper
import io.github.proify.lyricon.subscriber.util.ScreenStateMonitor
import io.github.proify.lyricon.lyric.style.BasicStyle
import io.github.proify.lyricon.lyric.style.LyricStyle
import io.github.proify.lyricon.lyric.style.TextStyle
import io.github.proify.lyricon.statusbarlyric.StatusBarLyric
import io.github.proify.lyricon.statusbarlyric.StatusColor
import io.github.proify.lyricon.xposed.logger.YLog
import io.github.proify.lyricon.xposed.systemui.hook.ClockViewFinder
import io.github.proify.lyricon.xposed.systemui.hook.OplusCapsuleHooker
import io.github.proify.lyricon.xposed.systemui.hook.StatusBarLyricTouchRouter
import io.github.proify.lyricon.xposed.systemui.hook.StatusBarColorMonitor
import io.github.proify.lyricon.xposed.systemui.lyric.LyricViewController.isPlaying
import io.github.proify.lyricon.xposed.systemui.lyric.control.LyricControlPopup
import io.github.proify.lyricon.xposed.systemui.util.OnColorChangeListener
import io.github.proify.lyricon.xposed.systemui.util.ViewVisibilityController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

/**
 * 状态栏歌词视图控制器：负责歌词视图的注入、位置锚定及显隐逻辑
 */
@SuppressLint("DiscouragedApi")
class StatusBarViewController(
    val statusBarView: ViewGroup,
    var currentLyricStyle: LyricStyle
) : ScreenStateMonitor.ScreenStateListener {
    companion object {
        const val TAG = "StatusBarViewController"
    }

    val context: Context = statusBarView.context.applicationContext
    val visibilityController: ViewVisibilityController = ViewVisibilityController(statusBarView)
    val lyricView: StatusBarLyric by lazy { createLyricView(currentLyricStyle) }

    private var lastAnchor = ""
    private var lastInsertionOrder = -1
    private var internalRemoveLyricViewFlag = false
    private var lastHighlightView: View? = null
    private var colorMonitorView: View? = null
    private var coverColorPaletteResult: ColorPaletteResult? = null
    private var systemStatusBarColor: SystemStatusBarColor? = null

    private val colorChangeListener = object : OnColorChangeListener {

        private var colorFingerprint: String? = null
        override fun onColorChanged(color: Int, darkIntensity: Float) {
            val colorFingerprint = color.toString() + darkIntensity
            if (colorFingerprint == this.colorFingerprint) return
            this.colorFingerprint = colorFingerprint

            updateStatusColor(SystemStatusBarColor(color, darkIntensity))
        }
    }

    private val onGlobalLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        applyVisibilityRulesNow()
        StatusBarLyricTouchRouter.updateTouchBounds(this)
//        // 无副作用复核:布局事件也是状态栏颜色可能的变更时机(补救观察点)
//        StatusBarColorMonitor.refresh()
    }

    // --- 生命周期与初始化 ---
    fun onCreate() {
        statusBarView.addOnAttachStateChangeListener(statusBarAttachListener)
        statusBarView.viewTreeObserver.addOnGlobalLayoutListener(onGlobalLayoutListener)
        lyricView.addOnAttachStateChangeListener(lyricAttachListener)
        ScreenStateMonitor.addListener(this)
        lyricView.isSleepMode = ScreenStateMonitor.state == ScreenStateMonitor.ScreenState.OFF
        lyricView.onPlayingChanged = { _ -> }

        // 手势控制:读取偏好并绑定回调,手势动作可配置
        refreshGestureConfig()
        lyricView.gestureListener = { gesture ->
            LyricGestureActions.dispatch(gesture, gestureTouchAnchor() ?: lyricView)
        }

        StatusBarColorMonitor.bindStatusBar(statusBarView)
        colorMonitorView = getClockView()
        StatusBarColorMonitor.bindClockView(colorMonitorView)
        StatusBarColorMonitor.addListener(colorChangeListener)

        statusBarView.doOnAttach {
            checkLyricViewExists()
            StatusBarLyricTouchRouter.register(this)
        }
        YLog.info(tag = TAG, "Lyric view created for $statusBarView")
    }

    fun onDestroy() {
        StatusBarLyricTouchRouter.unregister(this)
        coverJob?.cancel()
        statusBarView.removeOnAttachStateChangeListener(statusBarAttachListener)
        statusBarView.viewTreeObserver.removeOnGlobalLayoutListener(onGlobalLayoutListener)
        lyricView.removeOnAttachStateChangeListener(lyricAttachListener)
        ScreenStateMonitor.removeListener(this)
        lyricView.onPlayingChanged = null
        lyricView.gestureListener = null
        lyricView.setOnClickListener(null)
        LyricControlPopup.dismissIfOwnedBy(lyricView)
        StatusBarColorMonitor.removeListener(colorChangeListener)
        colorMonitorView?.let { StatusBarColorMonitor.unbindClockView(it) }
        colorMonitorView = null
        YLog.info(tag = TAG, "Lyric view destroyed for $statusBarView")
    }

    // --- 核心业务逻辑 ---

    /**
     * 更新状态栏颜色，内部决定最终颜色
     */
    internal fun updateStatusColor(systemStatusBarColor: SystemStatusBarColor) {
        this.systemStatusBarColor = systemStatusBarColor

        // 悬浮窗未播放歌词需要跟随系统亮暗切换黑/白（实色，不做半透明），此处把系统配色同步给悬浮窗控制器
        FloatingLyricController.setStatusColor(
            StatusColor(
                color = intArrayOf(systemStatusBarColor.color),
                translucentColor = intArrayOf(systemStatusBarColor.color),
                darkIntensity = systemStatusBarColor.darkIntensity
            )
        )

        val textStyle = currentLyricStyle.packageStyle.text
        lyricView.apply {
            currentStatusColor.apply {
                this.darkIntensity = systemStatusBarColor.darkIntensity

                val coverColorPaletteResult = coverColorPaletteResult
                when {
                    coverColorPaletteResult != null
                            && textStyle.enableExtractCoverTextColor
                            && textStyle.enableExtractCoverTextGradient -> {
                        val themeColors = coverColorPaletteResult
                            .let { if (isLightMode) it.lightModeColors else it.darkModeColors }

                        val gradient = themeColors.swatches

                        this.color = gradient
                    }

                    coverColorPaletteResult != null
                            && textStyle.enableExtractCoverTextColor -> {
                        val themeColors = coverColorPaletteResult
                            .let { if (isLightMode) it.lightModeColors else it.darkModeColors }

                        this.color = intArrayOf(themeColors.primary)
                    }

                    else -> {
                        this.color = intArrayOf(systemStatusBarColor.color)
                    }
                }

                // 未播放部分与悬浮窗保持一致：使用系统配色实色（亮色黑 / 暗色白），不做半透明
                this.translucentColor = intArrayOf(systemStatusBarColor.color)

                // 供「副行应用封面取色」使用：仅在存在封面配色时下发
                textView.setCoverColorScheme(resolveCoverColorScheme(isLightMode, textStyle))
            }
            setStatusBarColor(currentStatusColor)
        }
    }

    /** 解析封面配色；无封面配色时返回 null */
    private fun resolveCoverColorScheme(
        isLightMode: Boolean,
        textStyle: TextStyle
    ): IntArray? {
        val palette = coverColorPaletteResult ?: return null
        val themeColors = if (isLightMode) palette.lightModeColors else palette.darkModeColors
        return if (textStyle.enableExtractCoverTextGradient) {
            themeColors.swatches
        } else {
            intArrayOf(themeColors.primary)
        }
    }

    /**
     * 更新歌词样式及位置，若锚点或顺序变化则重新注入视图
     */
    fun updateLyricStyle(lyricStyle: LyricStyle) {
        this.currentLyricStyle = lyricStyle
        val basicStyle = lyricStyle.basicStyle

        val needUpdateLocation = lastAnchor != basicStyle.anchor
                || lastInsertionOrder != basicStyle.insertionOrder
                || !lyricView.isAttachedToWindow

        if (needUpdateLocation) {
            YLog.info(
                TAG,
                "Lyric location changed: ${basicStyle.anchor}, order ${basicStyle.insertionOrder}"
            )
            updateLocation(basicStyle)
        }
        lyricView.updateStyle(lyricStyle)
        // 翻译行显示受样式控制，样式热更新后立即应用
        LyricViewController.applyTranslationVisibility(lyricView, lyricStyle.packageStyle.text)
        refreshGestureConfig()

        systemStatusBarColor?.let { updateStatusColor(it) }
    }

    private val coverScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var coverJob: Job? = null

    fun updateCoverThemeColors(coverFile: File?) {
        coverJob?.cancel()
        coverColorPaletteResult = null
        systemStatusBarColor?.let { updateStatusColor(it) }
        coverJob = coverScope.launch {
            coverColorPaletteResult = ColorExtractor.extract(coverFile)
            systemStatusBarColor?.let { updateStatusColor(it) }
        }
    }

    /**
     * 处理视图注入逻辑：根据 BasicStyle 寻找锚点并插入歌词视图
     */
    private fun updateLocation(baseStyle: BasicStyle) {
        val anchor = baseStyle.anchor
        val anchorId = context.resources.getIdentifier(anchor, "id", context.packageName)
        val anchorView = statusBarView.findViewById<View>(anchorId) ?: return run {
            YLog.error(TAG, "Lyric anchor view $anchor not found")
        }

        val anchorParent = anchorView.parent as? ViewGroup ?: return run {
            YLog.error(TAG, "Lyric anchor parent not found")
        }

        // 标记内部移除，避免触发冗余的 detach 逻辑
        internalRemoveLyricViewFlag = true

        (lyricView.parent as? ViewGroup)?.removeView(lyricView)

        val anchorIndex = anchorParent.indexOfChild(anchorView)

        val lp = lyricView.layoutParams ?: run {
            val width = baseStyle.getAutoWidth(
                context.isLandScape(),
                isOplusCapsuleShowing = OplusCapsuleHooker.isShowing
            ).dp

            ViewGroup.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        // 执行插入：在前或在后
        val targetIndex =
            if (baseStyle.insertionOrder == BasicStyle.INSERTION_ORDER_AFTER) anchorIndex + 1
            else anchorIndex
        anchorParent.addView(lyricView, targetIndex, lp)

        lyricView.updateVisibility()
        lastAnchor = anchor
        lastInsertionOrder = baseStyle.insertionOrder
        internalRemoveLyricViewFlag = false

        YLog.info(TAG, "Lyric injected: anchor $anchor, index $targetIndex")
    }

    fun checkLyricViewExists() {
        if (lyricView.isAttachedToWindow) return
        lastAnchor = ""
        lastInsertionOrder = -1
        updateLyricStyle(currentLyricStyle)
    }

    // --- 辅助方法 ---

    private fun getClockView(): View? = ClockViewFinder.find(statusBarView)

    internal fun gestureClockAnchor(): View? = getClockView()?.takeIf { it.isShown }

    internal fun canRouteGestures(): Boolean =
        lyricView.gestureEnabled && !lyricView.isHideOnLockScreen()

    internal fun needsGestureRecoveryArea(): Boolean =
        !isPlaying || !LyricPrefs.baseStyle.lyricsEnabled ||
                LyricPrefs.floatingBaseStyle.manualFloatingLyricEnabled

    internal fun gestureTouchAnchor(): View? {
        if (!canRouteGestures()) return null
        if (lyricView.isShown && !lyricView.isDisabledVisible) return lyricView
        // Keep the same gestures available after pause, hiding lyrics or switching to floating mode.
        if (needsGestureRecoveryArea()) {
            return getClockView()?.takeIf { it.isShown }
        }
        return null
    }

    private var wasPlayingBeforeVisibilityUpdate: Boolean = false

    fun computeShouldApplyPlayingRules(): Boolean {
        if (!LyricPrefs.baseStyle.lyricsEnabled) return false
        if (LyricPrefs.floatingBaseStyle.manualFloatingLyricEnabled) return false
        return isPlaying && when {
            lyricView.isDisabledVisible -> !lyricView.isHideOnLockScreen()
            lyricView.isVisible -> true
            else -> false
        }
    }

    private fun applyVisibilityRulesNow() {
        val isPlaying = computeShouldApplyPlayingRules()
        fun apply() {
            visibilityController.applyVisibilityRules(
                rules = currentLyricStyle.basicStyle.visibilityRules,
                isPlaying = isPlaying
            )
        }

        if (!isPlaying) {
            // 仅在之前是播放状态时才更新（避免重复更新非播放状态的隐藏逻辑）
            if (wasPlayingBeforeVisibilityUpdate) {
                apply()
                wasPlayingBeforeVisibilityUpdate = false
            }
        } else {
            apply()
            wasPlayingBeforeVisibilityUpdate = true
        }
    }

    private fun createLyricView(style: LyricStyle) =
        StatusBarLyric(context, style, getClockView() as? TextView)

    // --- 手势控制 ---

    /** Refresh gesture switches; actions use the shared mapping at dispatch time. */
    internal fun refreshGestureConfig() {
        lyricView.gestureEnabled = LyricPrefs.gestureEnabled
        lyricView.hapticEnabled = LyricPrefs.gestureHapticEnabled
        lyricView.setOnClickListener(null)
        lyricView.isClickable = lyricView.gestureEnabled
    }

    fun highlightView(idName: String?) {
        YLog.info(TAG, "Highlighting view id:$idName")

        lastHighlightView?.background = null
        if (idName.isNullOrBlank()) return

        val id = ResourceMapper.getIdByName(context, idName)
        statusBarView.findViewById<View>(id)?.let { view ->
            view.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor("#FF3582FF".toColorInt())
                cornerRadius = 20.dp.toFloat()
            }
            lastHighlightView = view
        } ?: YLog.error(TAG, "Highlight target $idName not found")
    }

    private val lyricAttachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            YLog.info(TAG, "LyricView attached")
        }

        override fun onViewDetachedFromWindow(v: View) {
            YLog.info(TAG, "LyricView detached")
            if (!internalRemoveLyricViewFlag) {
                checkLyricViewExists()
            } else {
                YLog.info(TAG, "LyricView detached by internal flag")
            }
        }
    }

    private val statusBarAttachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) { StatusBarLyricTouchRouter.register(this@StatusBarViewController) }
        override fun onViewDetachedFromWindow(v: View) { StatusBarLyricTouchRouter.unregister(this@StatusBarViewController) }
    }

    override fun onScreenOn() {
        lyricView.updateVisibility()
        lyricView.isSleepMode = false
    }

    override fun onScreenOff() {
        lyricView.updateVisibility()
        lyricView.isSleepMode = true
    }

    override fun onScreenUnlocked() {
        lyricView.updateVisibility()
        lyricView.isSleepMode = false
    }

    fun onDisableStateChanged(shouldHide: Boolean) {
        lyricView.isDisabledVisible = shouldHide
        applyVisibilityRulesNow()
    }

    override fun equals(other: Any?): Boolean =
        (this === other) ||
                (other is StatusBarViewController && statusBarView == other.statusBarView)

    override fun hashCode(): Int = 31 * 17 + statusBarView.hashCode()

    data class SystemStatusBarColor(val color: Int, val darkIntensity: Float)
}
