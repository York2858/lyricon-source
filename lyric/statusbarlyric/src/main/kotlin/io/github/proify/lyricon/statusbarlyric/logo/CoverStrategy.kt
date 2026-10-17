/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.statusbarlyric.logo

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.LinearInterpolator
import io.github.proify.android.extensions.dp
import io.github.proify.lyricon.common.util.CoverImages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.proify.lyricon.lyric.style.LogoStyle

/**
 * 策略：显示专辑封面。
 * 支持圆形旋转（唱片模式）和圆角矩形，不跟随状态栏颜色。
 */
class CoverStrategy(
    private val view: SuperLogo
) : ILogoStrategy {

    companion object {
        private const val DEFAULT_ROTATION_DURATION_MS = 12_000L
        private val SQUIRCLE_CORNER_RADIUS_DP by lazy { 3.5f.dp.toFloat() }
    }

    private var rotationAnimator: ObjectAnimator? = null
    private var lastFileSignature: String? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loadJob: Job? = null

    override var isEffective: Boolean = false
        private set

    var style: Int = LogoStyle.STYLE_COVER_CIRCLE

    override fun updateContent() {
        if (view.imageTintList != null) view.imageTintList = null
        loadJob?.cancel()
        val file = view.coverFile
        if (file == null) {
            view.setImageDrawable(null)
            isEffective = false
            lastFileSignature = null
        } else {
            val maxSize = maxOf(view.width, view.height, 64).coerceAtMost(512)
            loadJob = scope.launch {
                withContext(Dispatchers.IO) {
                    val signature = CoverImages.key(file)
                    val alreadyLoaded = withContext(Dispatchers.Main) {
                        signature == lastFileSignature && view.drawable != null
                    }
                    if (alreadyLoaded) return@withContext
                    val bitmap = CoverImages.decode(file, maxSize)
                    var adopted = false
                    try {
                        withContext(Dispatchers.Main) {
                            if (view.coverFile == file) {
                                view.setImageBitmap(bitmap)
                                adopted = true
                                lastFileSignature = signature
                                isEffective = bitmap != null
                                stopAnimation(true)
                                applyStyleAndAnimation()
                                view.updateVisibility()
                            }
                        }
                    } finally {
                        if (!adopted) bitmap?.recycle()
                    }
                }
            }
        }
        applyStyleAndAnimation()
        view.updateVisibility()
    }

    private fun applyStyleAndAnimation() {
        val currentStyle =
            view.lyricStyle?.packageStyle?.logo?.style ?: LogoStyle.Companion.STYLE_COVER_CIRCLE
        val oldStyle = this.style
        this.style = currentStyle

        // 设置裁剪轮廓
        applyOutlineProvider(currentStyle)

        // 如果从圆形切换到其他样式，必须强制重置旋转角度
        if (oldStyle == LogoStyle.STYLE_COVER_CIRCLE && currentStyle != LogoStyle.Companion.STYLE_COVER_CIRCLE) {
            view.rotation = 0f
        }

        // 检查动画状态
        checkAnimationState()
    }

    override fun onColorUpdate() {
        // 封面保持原色，不应用 Tint
    }

    override fun onAttach() {
        // 恢复视图状态
        updateContent()
        checkAnimationState()
    }

    override fun onDetach() {
        loadJob?.cancel()
        loadJob = null
        view.setImageDrawable(null)
        lastFileSignature = null
        isEffective = false
        stopAnimation()
        // 可以在此释放图片，下次 onAttach 时会通过 updateContent 重新加载
        // setImageDrawable(null)
    }

    override fun onVisibilityChanged(visible: Boolean) {
        if (visible) checkAnimationState() else stopAnimation()
    }

    private fun applyOutlineProvider(style: Int) {
        val provider = when (style) {
            LogoStyle.STYLE_COVER_CIRCLE -> object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }

            LogoStyle.STYLE_COVER_SQUIRCLE -> object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(
                        0,
                        0,
                        view.width,
                        view.height,
                        SQUIRCLE_CORNER_RADIUS_DP
                    )
                }
            }

            else -> null
        }
        view.outlineProvider = provider
        view.clipToOutline = provider != null
    }

    private fun checkAnimationState() {
        if (view.animationsEnabled && view.windowVisibility == View.VISIBLE && view.isAttachedToWindow && view.isShown && isEffective && style == LogoStyle.Companion.STYLE_COVER_CIRCLE) {
            startAnimation()
        } else {
            stopAnimation()
        }
    }

    private fun startAnimation() {
        if (rotationAnimator?.isRunning == true) return

        rotationAnimator =
            ObjectAnimator.ofFloat(
                view, "rotation",
                view.rotation,
                view.rotation + 360f
            ).apply {
                duration = DEFAULT_ROTATION_DURATION_MS
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
                interpolator = LinearInterpolator()
                start()
            }
    }

    private fun stopAnimation(resetRotation: Boolean = false) {
        rotationAnimator?.cancel()
        rotationAnimator = null

        // 注意：这里不自动重置 rotation 为 0，以便暂停后恢复时视觉连贯。
        // 彻底重置由 LyricLogoView.resetViewAttributes() 在切换策略时处理。

        if (resetRotation) view.rotation = 0f
    }
}
