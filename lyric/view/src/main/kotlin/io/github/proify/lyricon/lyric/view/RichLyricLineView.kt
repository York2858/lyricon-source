/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.lyric.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.Gravity
import android.widget.LinearLayout
import androidx.core.graphics.withScale
import androidx.core.view.forEach
import io.github.proify.lyricon.lyric.model.interfaces.IRichLyricLine
import io.github.proify.lyricon.lyric.view.line.LyricLineView
import kotlin.math.ceil

@SuppressLint("ViewConstructor")
class RichLyricLineView(
    context: Context,
    var displayTranslation: Boolean = true,
    var enableRelativeProgress: Boolean = false,
    var enableRelativeProgressHighlight: Boolean = false,
    var displayRoma: Boolean = true
) : LinearLayout(context), UpdatableColor {

    val main = LyricLineView(context)
    val secondary = LyricLineView(context).apply { visibleIfChanged = false }
    val tertiary = LyricLineView(context).apply { visibleIfChanged = false }

    /** 设置主/副/第三行内容是否水平居中（悬浮窗场景） */
    fun setCenterAligned(center: Boolean) {
        main.isCenterAligned = center
        secondary.isCenterAligned = center
        tertiary.isCenterAligned = center
    }

    var alwaysShowSecondary = false
    var alwaysShowTertiary = false

    var renderScale = 1.0f
        private set

    private var fittedRenderScale = 1f

    private val assembler = LyricLineAssembler(
        displayTranslation, displayRoma,
        enableRelativeProgress, enableRelativeProgressHighlight
    )

    private var animationTransition = false
    private var pendingLine: IRichLyricLine? = null
    private var pendingPosition: Long? = null
    private var requestMarquee = false

    var rawLine: IRichLyricLine? = null

    var line: IRichLyricLine?
        get() = rawLine
        set(value) {
            rawLine = value
            requestMarquee = false
            if (animationTransition) {
                pendingLine = value
            } else {
                refreshLines()
            }
        }

    init {
        orientation = VERTICAL
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        addView(main, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(secondary, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(tertiary, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        updateLayoutTransitionX()
    }

    fun reset() {
        line = null
        renderScale = 1.0f
        animationTransition = false
        pendingLine = null
        pendingPosition = null
        alwaysShowSecondary = false
        alwaysShowTertiary = false
        refreshLines()
    }

    fun beginAnimationTransition() {
        animationTransition = true
    }

    fun endAnimationTransition() {
        animationTransition = false
        if (pendingLine != null) {
            refreshLines()
            pendingPosition?.let { setPosition(it) }
        }
        pendingLine = null
        pendingPosition = null
    }

    fun setTransitionConfig(config: String?) {
        updateLayoutTransitionX(config)
    }

    fun notifyLineChanged() = refreshLines()

    fun seekTo(position: Long) {
        if (animationTransition) {
            pendingPosition = position; return
        }
        main.seekTo(position)
        secondary.seekTo(position)
        tertiary.seekTo(position)
    }

    fun setPosition(position: Long) {
        if (animationTransition) {
            pendingPosition = position; return
        }
        main.updatePosition(position)
        secondary.updatePosition(position)
        tertiary.updatePosition(position)
    }

    fun requestStartMarquee() {
        requestMarquee = true
        main.requestScroll()
        secondary.requestScroll()
        tertiary.requestScroll()
    }

    fun setStyle(style: LyricViewStyle) {
        val layoutChanged = assembler.layout != style.auxiliaryLayout
        assembler.layout = style.auxiliaryLayout
        assembler.updateFlags(
            displayTranslation, displayRoma,
            style.primary.relativeProgress, style.primary.relativeHighlight
        )
        enableRelativeProgress = style.primary.relativeProgress
        enableRelativeProgressHighlight = style.primary.relativeHighlight

        applyLineStyle(
            main,
            style.primary,
            style.highlight,
            style.marquee,
            style.gradient,
            style.fadingEdge,
            style.wordMotion
        )
        applyLineStyle(
            secondary,
            style.secondary,
            style.highlight,
            style.marquee,
            style.gradient,
            style.fadingEdge,
            style.wordMotion
        )
        applyLineStyle(
            tertiary,
            style.secondary,
            style.highlight,
            style.marquee,
            style.gradient,
            style.fadingEdge,
            style.wordMotion
        )
        if (layoutChanged) refreshLines()
    }

    override fun updateColor(primary: IntArray, background: IntArray, highlight: IntArray) {
        forEach { if (it is UpdatableColor) it.updateColor(primary, background, highlight) }
    }

    fun setMainLyricPlayListener(listener: LyricPlayListener?) {
        main.playListener = listener
    }

    fun setSecondaryLyricPlayListener(listener: LyricPlayListener?) {
        secondary.playListener = listener
    }

    fun setTertiaryLyricPlayListener(listener: LyricPlayListener?) {
        tertiary.playListener = listener
    }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val width = MeasureSpec.getSize(wSpec)
        val widthMode = MeasureSpec.getMode(wSpec)
        val unlimitedHeight = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        fun measureContent(scale: Float) {
            val logicalWidth = if (scale > 0f) ceil(width / scale).toInt() else width
            super.onMeasure(MeasureSpec.makeMeasureSpec(logicalWidth, widthMode), unlimitedHeight)
        }

        // LinearLayout normally spends the height on the first row and gives the
        // translation only what remains. Measure every row at its full animated
        // height first, then fit the complete stack into the status bar together.
        measureContent(renderScale)
        val contentHeight = measuredHeight
        val desiredHeight = maxOf(contentHeight, ceil(contentHeight * renderScale).toInt())
        val height = resolveSize(desiredHeight, hSpec)
        fittedRenderScale = LyricHeightFit.scale(contentHeight, height, renderScale)
        if (fittedRenderScale != renderScale) measureContent(fittedRenderScale)
        setMeasuredDimension(width, height)
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (fittedRenderScale != 1.0f) {
            canvas.withScale(fittedRenderScale, fittedRenderScale, 0f, height / 2f) {
                super.dispatchDraw(this)
            }
        } else {
            super.dispatchDraw(canvas)
        }
    }

    fun setRenderScale(scale: Float) {
        if (renderScale != scale) {
            renderScale = scale
            requestLayout()
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        reset()
    }

    private var oldLine: IRichLyricLine? = null

    private fun refreshLines() {
        assembler.updateFlags(
            displayTranslation, displayRoma,
            enableRelativeProgress, enableRelativeProgressHighlight
        )
        if (oldLine === line && line.isTitleLine()) return
        oldLine = line

        val mainResult = assembler.buildMain(line)
        main.setLyric(mainResult.line)
        main.isScrollOnly = mainResult.isScrollOnly

        val secResult = assembler.buildSecondary(line)
        alwaysShowSecondary = secResult.alwaysShow
        secondary.visibleIfChanged = secResult.alwaysShow
        secondary.setLyric(secResult.line)
        secondary.isScrollOnly = secResult.isScrollOnly

        val terResult = assembler.buildTertiary(line)
        alwaysShowTertiary = terResult.alwaysShow
        tertiary.visibleIfChanged = terResult.alwaysShow
        tertiary.setLyric(terResult.line)
        tertiary.isScrollOnly = false

        if (requestMarquee) requestStartMarquee()
    }

    private fun applyLineStyle(
        view: LyricLineView, text: TextLook, highlight: Highlight,
        marquee: Marquee, gradient: Boolean, fadingEdge: Int, wordMotion: WordMotion
    ) {
        view.wordMotion = wordMotion
        view.configureWith(text, highlight, marquee, gradient, fadingEdge)
    }

    private fun updateLayoutTransitionX(config: String? = LayoutTransitionX.TRANSITION_CONFIG_SMOOTH) {
        layoutTransition = LayoutTransitionX(config).apply { setAnimateParentHierarchy(true) }
    }
}
