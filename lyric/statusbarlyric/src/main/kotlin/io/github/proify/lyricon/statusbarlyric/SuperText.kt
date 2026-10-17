/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.statusbarlyric

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.widget.TextView
import androidx.core.view.forEach
import androidx.core.view.isEmpty
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import io.github.proify.android.extensions.dp
import io.github.proify.android.extensions.sp
import io.github.proify.lyricon.lyric.style.LyricStyle
import io.github.proify.lyricon.lyric.style.TextStyle
import io.github.proify.lyricon.lyric.view.AnimParams
import io.github.proify.lyricon.lyric.view.Highlight
import io.github.proify.lyricon.lyric.view.LyricPlayerView
import io.github.proify.lyricon.lyric.view.LyricViewStyle
import io.github.proify.lyricon.lyric.view.Marquee
import io.github.proify.lyricon.lyric.view.RichLyricLineView
import io.github.proify.lyricon.lyric.view.TextLook
import io.github.proify.lyricon.lyric.view.TitleSlot
import io.github.proify.lyricon.lyric.view.WordMotion
import java.io.File
import kotlin.math.max
import kotlin.math.min

class SuperText(context: Context) : LyricPlayerView(context) {

    @Suppress("unused")
    companion object {
        const val VIEW_TAG: String = "lyricon:text_view"
        private const val TAG = "SuperText"
        private const val DEBUG = false
        private const val MAX_FONT_WEIGHT: Int = 1000
        private const val NORMAL_FONT_WEIGHT: Int = 400
        private const val BOLD_FONT_WEIGHT: Int = 700
    }

    var linkedTextView: TextView? = null

    var eventListener: EventListener? = null

    private var currentStatusColor = StatusColor()
    private var currentLyricStyle: LyricStyle? = null

    /** 封面提取出的配色，供「副行应用封面取色」使用；为空表示暂无封面配色 */
    private var coverColorScheme: IntArray? = null

    interface EventListener {
        fun enteringInterludeMode(duration: Long)
        fun exitInterludeMode()
    }

    init {
        tag = VIEW_TAG
    }

    override fun enteringInterludeMode(duration: Long) {
        super.enteringInterludeMode(duration)
        eventListener?.enteringInterludeMode(duration)
    }

    override fun exitInterludeMode() {
        super.exitInterludeMode()
        eventListener?.exitInterludeMode()
    }

    fun applyStyle(style: LyricStyle) {
        this.currentLyricStyle = style
        val textStyle = style.packageStyle.text

        setTransitionConfig(textStyle.transitionConfig)
        updateContainerLayout(textStyle)
        setStyle(buildViewStyle(style))
    }

    /**
     * 构建视图样式。
     *
     * 抽出以便在仅副行配色变化时重新下发整套样式
     * （[updateColor] 只会把主行颜色同步给副行，无法单独设置副行颜色）。
     */
    private fun buildViewStyle(style: LyricStyle): LyricViewStyle {
        val textStyle = style.packageStyle.text
        val resolvedTypeface = resolveTypeface(textStyle)
        val fontSize = if (textStyle.textSize > 0) {
            textStyle.textSize.sp
        } else {
            linkedTextView?.textSize ?: 14f.sp
        }

        return LyricViewStyle(
            primary = TextLook(
                color = resolvePrimaryColor(textStyle),
                size = fontSize,
                typeface = resolvedTypeface,
                relativeProgress = textStyle.relativeProgress,
                relativeHighlight = textStyle.relativeProgressHighlight,
            ),
            secondary = TextLook(
                color = resolveSecondaryColor(textStyle),
                size = fontSize * 0.76f,
                typeface = resolvedTypeface,
            ),
            highlight = Highlight(
                background = resolveBgColor(textStyle),
                foreground = resolveHighlightColor(textStyle),
            ),
            marquee = buildMarquee(textStyle),
            wordMotion = WordMotion(
                enabled = textStyle.wordMotionEnabled,
                cjkLiftFactor = textStyle.wordMotionCjkLiftFactor,
                cjkWaveFactor = textStyle.wordMotionCjkWaveFactor,
                latinLiftFactor = textStyle.wordMotionLatinLiftFactor,
                latinWaveFactor = textStyle.wordMotionLatinWaveFactor,
            ),
            gradient = textStyle.gradientProgressStyle,
            fadingEdge = textStyle.fadingEdgeLength.coerceAtLeast(0).dp,
            scaleMultiLine = textStyle.scaleInMultiLine,
            animation = AnimParams(
                enabled = style.packageStyle.anim.enable,
                presetId = style.packageStyle.anim.id,
                speed = style.packageStyle.anim.speed,
            ),
            placeholder = when (textStyle.placeholderFormat
                ?: TextStyle.Defaults.PLACEHOLDER_FORMAT) {
                TextStyle.PlaceholderFormat.NONE -> TitleSlot.NONE
                TextStyle.PlaceholderFormat.NAME -> TitleSlot.NAME
                TextStyle.PlaceholderFormat.NAME_ARTIST -> TitleSlot.NAME_ARTIST
                else -> TitleSlot.NAME_ARTIST
            }
        )
    }

    fun setStatusBarColor(color: StatusColor) {
        this.currentStatusColor = color
        refreshVisualColors()
    }

    /**
     * 设置封面提取出的配色。
     *
     * 供「副行应用封面取色」使用：开启后副行独立跟随封面配色，
     * 不受主行自定义颜色/彩虹渐变影响。
     *
     * @param colors 封面配色（可为渐变色列表），传 null 表示无可用封面配色
     */
    fun setCoverColorScheme(colors: IntArray?) {
        this.coverColorScheme = colors
        refreshVisualColors()
    }

    private fun refreshVisualColors() {
        val style = currentLyricStyle ?: return
        val textStyle = style.packageStyle.text

        val primary = resolvePrimaryColor(textStyle)
        // [updateColor] 会把副行颜色同步为主行颜色，因此副行配色独立时必须重新下发整套样式
        updateColor(
            primary = primary,
            background = resolveBgColor(textStyle),
            highlight = resolveHighlightColor(textStyle)
        )

        if (resolveSecondaryColor(textStyle).contentEquals(primary)) return
        setStyle(buildViewStyle(style))
    }

    private fun updateContainerLayout(textStyle: TextStyle) {
        val margins = textStyle.margins
        val paddings = textStyle.paddings

        val params = (layoutParams as? MarginLayoutParams)
            ?: MarginLayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)

        params.setMargins(
            margins.left.dp,
            margins.top.dp,
            margins.right.dp,
            margins.bottom.dp
        )
        layoutParams = params

        updatePadding(
            paddings.left.dp,
            paddings.top.dp,
            paddings.right.dp,
            paddings.bottom.dp
        )
    }

    private fun buildMarquee(textStyle: TextStyle) = Marquee(
        speed = textStyle.marqueeSpeed,
        spacing = textStyle.marqueeGhostSpacing,
        initialDelay = textStyle.marqueeInitialDelay,
        loopDelay = textStyle.marqueeLoopDelay,
        repeatCount = if (textStyle.marqueeRepeatUnlimited) -1 else textStyle.marqueeRepeatCount,
        stopAtEnd = textStyle.marqueeStopAtEnd,
    )

    private fun resolvePrimaryColor(textStyle: TextStyle): IntArray {
        if (textStyle.enableRainbowTextColor) {
            return resolveRainbowColors(textStyle).normal
        }
        val customColor = textStyle.color(currentStatusColor.isLightMode)
        return if (textStyle.enableCustomTextColor && customColor?.normal?.isNotEmpty() == true) {
            customColor.normal
        } else {
            currentStatusColor.color
        }
    }

    /**
     * 解析副行颜色。
     *
     * 优先级：副行封面取色 → 副行彩虹渐变 → 自定义背景色 → 与主行一致。
     *
     * 主行正在跟随封面取色/彩虹渐变时，副行开关关闭会回落到「自定义背景色」
     * （未自定义时使用状态栏配色）；主行未使用特殊配色时副行与主行保持一致。
     * 状态栏与悬浮窗样式共用同一套副行配色逻辑。
     */
    private fun resolveSecondaryColor(textStyle: TextStyle): IntArray {
        if (textStyle.enableExtractCoverColorForSecondary) {
            coverColorScheme?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        if (textStyle.enableRainbowTextColor && textStyle.enableRainbowColorForSecondary) {
            return resolveRainbowColors(textStyle).normal
        }
        if (primaryUsesSpecialPalette(textStyle)) {
            // 「自定义背景色」即渲染层的未播放配色（自定义颜色-背景色，未配置时为半透明状态栏配色）
            return resolveSecondaryFallbackColor(textStyle)
        }
        return resolvePrimaryColor(textStyle)
    }

    /** 主行是否正在使用封面取色 / 彩虹渐变等特殊配色 */
    private fun primaryUsesSpecialPalette(textStyle: TextStyle): Boolean {
        if (textStyle.enableExtractCoverTextColor || textStyle.enableRainbowTextColor) return true
        val customColor = textStyle.color(currentStatusColor.isLightMode) ?: return false
        return textStyle.enableCustomTextColor && customColor.normal.isNotEmpty()
    }

    private fun resolveBgColor(textStyle: TextStyle): IntArray {
        // 未播放部分沿用既有未播放配色（亮色黑 / 暗色白；用户自定义背景色优先），不使用淡色彩虹
        return resolveSecondaryFallbackColor(textStyle)
    }

    /** 副行关闭彩虹时沿用原有底色，避免主行的彩虹底层使副行也变成彩虹。 */
    private fun resolveSecondaryFallbackColor(textStyle: TextStyle): IntArray {
        val customColor = textStyle.color(currentStatusColor.isLightMode)
        return if (textStyle.enableCustomTextColor && customColor?.background?.isNotEmpty() == true) {
            customColor.background
        } else {
            currentStatusColor.translucentColor
        }
    }

    private fun resolveHighlightColor(textStyle: TextStyle): IntArray {
        if (textStyle.enableRainbowTextColor) {
            return resolveRainbowColors(textStyle).highlight
        }
        val customColor = textStyle.color(currentStatusColor.isLightMode)
        return if (textStyle.enableCustomTextColor && customColor?.highlight?.isNotEmpty() == true) {
            customColor.highlight
        } else {
            currentStatusColor.color
        }
    }

    private fun resolveRainbowColors(textStyle: TextStyle) = RainbowPalette.resolve(
        lightMode = currentStatusColor.isLightMode,
        custom = textStyle.color(currentStatusColor.isLightMode)
    )

    private fun resolveTypeface(textStyle: TextStyle): Typeface {
        val baseTypeface = textStyle.typeFace?.takeIf { it.isNotBlank() }?.let { path ->
            val file = File(path)
            if (file.exists()) {
                runCatching { Typeface.createFromFile(file) }.getOrNull()
            } else null
        } ?: linkedTextView?.typeface ?: Typeface.DEFAULT

        val italic = textStyle.typeFaceItalic
        // 字重与「粗体」取较大值：设置过字重时粗体依然生效
        val requestedWeight =
            if (textStyle.fontWeight > 0) textStyle.fontWeight else NORMAL_FONT_WEIGHT
        val weight =
            if (textStyle.typeFaceBold) max(requestedWeight, BOLD_FONT_WEIGHT)
            else requestedWeight

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Typeface.create(baseTypeface, min(MAX_FONT_WEIGHT, weight), italic)
        } else {
            val styleFlag = when {
                weight >= BOLD_FONT_WEIGHT && italic -> Typeface.BOLD_ITALIC
                weight >= BOLD_FONT_WEIGHT -> Typeface.BOLD
                italic -> Typeface.ITALIC
                else -> Typeface.NORMAL
            }
            Typeface.create(baseTypeface, styleFlag)
        }
    }

    fun shouldShow(): Boolean {
        if (isEmpty()) return false
        var visibleCount = 0
        forEach {
            if (it.isVisible) {
                if (it is RichLyricLineView) {
                    if (it.main.isVisible || it.secondary.isVisible) visibleCount++
                } else {
                    visibleCount++
                }
            }
        }
        return visibleCount > 0
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
    }
}
