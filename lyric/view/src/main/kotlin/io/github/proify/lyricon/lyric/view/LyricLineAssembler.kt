/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.lyric.view

import io.github.proify.lyricon.lyric.model.LyricLine
import io.github.proify.lyricon.lyric.model.interfaces.IRichLyricLine
import io.github.proify.lyricon.lyric.model.lyricMetadataOf

internal class LyricLineAssembler(
    private var displayTranslation: Boolean = true,
    private var displayRoma: Boolean = true,
    private var enableRelativeProgress: Boolean = false,
    private var enableRelativeHighlight: Boolean = false,
) {
    private val wordBuilder = RelativeWordBuilder()
    var layout: AuxiliaryLineLayout = AuxiliaryLineLayout.TRANSLATION_FIRST

    fun updateFlags(displayTranslation: Boolean, displayRoma: Boolean,
                    enableRelativeProgress: Boolean, enableRelativeHighlight: Boolean) {
        this.displayTranslation = displayTranslation
        this.displayRoma = displayRoma
        this.enableRelativeProgress = enableRelativeProgress
        this.enableRelativeHighlight = enableRelativeHighlight
    }

    data class MainResult(val line: LyricLine, val isScrollOnly: Boolean)

    fun buildMain(source: IRichLyricLine?): MainResult {
        if (source == null) return MainResult(LyricLine(), false)

        val shouldGen = enableRelativeProgress && source.isTitleLine().not()
        val words = if (shouldGen) {
            wordBuilder.build(source, source.text, source.words)
        } else source.words

        val generated = words !== source.words
        val line = LyricLine(
            begin = source.begin, end = source.end, duration = source.duration,
            isAlignedRight = source.isAlignedRight, metadata = source.metadata,
            text = source.text, words = words
        )
        return MainResult(line, generated && !enableRelativeHighlight)
    }

    data class SecondaryResult(val line: LyricLine, val alwaysShow: Boolean, val isScrollOnly: Boolean)

    fun buildSecondary(source: IRichLyricLine?): SecondaryResult {
        if (source == null) return SecondaryResult(LyricLine(), false, false)

        var generated = false
        val line = LyricLine().apply {
            begin = source.begin; end = source.end; duration = source.duration
            isAlignedRight = source.isAlignedRight

            val hasTranslation = displayTranslation && (!source.translation.isNullOrBlank()
                || !source.translationWords.isNullOrEmpty())
            val hasRoma = displayRoma && !source.roma.isNullOrBlank()
            when {
                hasRoma && (layout != AuxiliaryLineLayout.TRANSLATION_FIRST || !hasTranslation) -> {
                    text = source.roma
                    val timedRoma = source.secondaryWords?.takeIf {
                        source.secondary?.trim() == source.roma?.trim()
                    }
                    words = wordBuilder.build(source, source.roma, timedRoma)
                    metadata = lyricMetadataOf("roma" to "true")
                    generated = true
                }
                hasTranslation -> {
                    text = source.translation
                    words = wordBuilder.build(source, source.translation, source.translationWords)
                    metadata = lyricMetadataOf("translation" to "true")
                    generated = words !== source.translationWords
                }
                !source.secondary.isNullOrBlank() || !source.secondaryWords.isNullOrEmpty() -> {
                    text = source.secondary
                    words = wordBuilder.build(source, source.secondary, source.secondaryWords)
                    generated = words !== source.secondaryWords
                }
            }
        }

        val hasContent = line.text?.isNotBlank() == true || !line.words.isNullOrEmpty()
        val isPlain = line.words?.isEmpty() == true
        val alwaysShow = hasContent && (
                isPlain || line.metadata?.getBoolean("translation") == true
                        || line.metadata?.getBoolean("roma") == true
                        || line.words?.firstOrNull()?.begin?.let { (it - source.begin) < 500 } == true
                )

        val auxiliary = line.metadata?.getBoolean("translation") == true || line.metadata?.getBoolean("roma") == true
        // Both auxiliary roles share the translation color/highlight/motion renderer.
        // A plain secondary caption retains the existing scroll-only policy.
        return SecondaryResult(line, alwaysShow, !auxiliary && generated && !enableRelativeHighlight)
    }

    data class TertiaryResult(val line: LyricLine, val alwaysShow: Boolean)

    /**
     * 悬浮窗同时具备罗马音和翻译时，罗马音占副行、翻译占第三行。
     */
    fun buildTertiary(source: IRichLyricLine?): TertiaryResult {
        if (source == null) return TertiaryResult(LyricLine(), false)
        if (layout != AuxiliaryLineLayout.ROMA_THEN_TRANSLATION || !displayRoma) return TertiaryResult(LyricLine(), false)
        val translationAvailable = displayTranslation &&
                (!source.translation.isNullOrBlank() || !source.translationWords.isNullOrEmpty())
        if (!translationAvailable) {
            return TertiaryResult(LyricLine(), false)
        }

        val roma = source.roma
        if (roma.isNullOrBlank()) return TertiaryResult(LyricLine(), false)

        val line = LyricLine().apply {
            begin = source.begin
            end = source.end
            duration = source.duration
            isAlignedRight = source.isAlignedRight
            text = source.translation
            words = wordBuilder.build(source, source.translation, source.translationWords)
            metadata = lyricMetadataOf("translation" to "true")
        }
        return TertiaryResult(line, true)
    }
}
