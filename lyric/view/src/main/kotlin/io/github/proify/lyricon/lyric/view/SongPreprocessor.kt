/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.lyric.view

import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.lyric.model.interfaces.IRichLyricLine
import io.github.proify.lyricon.lyric.model.lyricMetadataOf

internal class SongPreprocessor(private val placeholder: TitleSlot) {

    companion object {
        internal const val KEY_TITLE_LINE = "TitleLine"

        /** 视为「同一句」的时间轴容差：起始 1.5s / 结束 2s */
        private const val SAME_SENTENCE_BEGIN_TOLERANCE_MS = 1500L
        private const val SAME_SENTENCE_END_TOLERANCE_MS = 2000L
    }

    fun prepare(song: Song): List<TimedLine> {
        val filled = fillGap(song)
        val merged = mergeStandaloneRomaLines(filled.lyrics.orEmpty())
        return linkTimedLines(merged)
    }

    private fun linkTimedLines(lyrics: List<IRichLyricLine>): List<TimedLine> {
        val lines = mutableListOf<TimedLine>()
        var prev: TimedLine? = null
        lyrics.forEach { lyric ->
            val tl = TimedLine(lyric).also {
                it.previous = prev
                prev?.next = it
            }
            lines.add(tl)
            prev = tl
        }
        return lines
    }

    // ---------------------------------------------------------------------
    // 独立罗马音行配对
    // ---------------------------------------------------------------------

    /**
     * 把「独立成行的罗马音」并入相邻主歌词行的 [IRichLyricLine.roma]，
     * 并从结果中移除该罗马音行，避免它被当作独立歌词行渲染。
     *
     * 部分歌词源的罗马音是与主歌词交替出现的独立行（而不是同一行的 roma 字段），
     * 这里按「时间轴接近 + 文本形态」启发式配对。
     */
    private fun mergeStandaloneRomaLines(lyrics: List<IRichLyricLine>): List<IRichLyricLine> {
        if (lyrics.isEmpty()) return lyrics

        val consumed = BooleanArray(lyrics.size)
        val result = mutableListOf<IRichLyricLine>()

        for (index in lyrics.indices) {
            if (consumed[index]) continue
            val line = lyrics[index]

            // 1) 当前行是独立罗马音行：找相邻主行合并
            if (isLikelyStandaloneRomaLine(line)) {
                val baseIndex = findMainPairIndex(index, lyrics, consumed)
                consumed[index] = true
                if (baseIndex >= 0) {
                    consumed[baseIndex] = true
                    result.add(mergeRomaIntoBase(lyrics[baseIndex], line))
                } else {
                    result.add(line)
                }
                continue
            }

            // 2) 当前行是主行且自身没有 roma：找相邻独立罗马音行合并
            val romaIndex = if (line.roma.isNullOrBlank() && isLikelyMainLine(line)) {
                adjacentIndices(index, lyrics.size).firstOrNull { i ->
                    !consumed[i] && isTimingClose(line, lyrics[i]) && isLikelyStandaloneRomaLine(lyrics[i])
                }
            } else {
                null
            }

            consumed[index] = true
            if (romaIndex != null) {
                consumed[romaIndex] = true
                result.add(mergeRomaIntoBase(line, lyrics[romaIndex]))
            } else {
                result.add(line)
            }
        }
        return result
    }

    private fun adjacentIndices(index: Int, size: Int): List<Int> = listOfNotNull(
        (index - 1).takeIf { it >= 0 },
        (index + 1).takeIf { it < size }
    )

    /** 为独立罗马音行挑选最像「主歌词行」的相邻行。 */
    private fun findMainPairIndex(
        index: Int,
        lyrics: List<IRichLyricLine>,
        consumed: BooleanArray
    ): Int {
        val romaLine = lyrics[index]
        return adjacentIndices(index, lyrics.size)
            .filter { !consumed[it] }
            .filter { isTimingClose(romaLine, lyrics[it]) }
            .filter { !isLikelyStandaloneRomaLine(lyrics[it]) }
            .maxByOrNull { scoreAsMainLine(lyrics[it]) }
            ?: -1
    }

    /** 两行时间轴是否足够接近（视为同一句的两种写法）。 */
    private fun isTimingClose(a: IRichLyricLine, b: IRichLyricLine): Boolean =
        kotlin.math.abs(a.begin - b.begin) <= SAME_SENTENCE_BEGIN_TOLERANCE_MS &&
                kotlin.math.abs(a.end - b.end) <= SAME_SENTENCE_END_TOLERANCE_MS

    /** 打分：含翻译、含 CJK、非空文本都会提高「更像主行」的分数。 */
    private fun scoreAsMainLine(line: IRichLyricLine): Int {
        var score = 0
        if (line.secondary.isNullOrBlank()) {
            score += if (line.translation.isNullOrBlank()) 0 else 3
        }
        val text = line.text?.trim().orEmpty()
        if (containsCjk(text)) score += 2
        return if (text.isNotBlank()) score + 1 else score
    }

    /** 主行判定：有副行/翻译，或文本本身包含 CJK。 */
    private fun isLikelyMainLine(line: IRichLyricLine): Boolean {
        if (line.secondary.isNullOrBlank() && line.translation.isNullOrBlank()) {
            val text = line.text?.trim().orEmpty()
            return text.isNotBlank() && containsCjk(text)
        }
        return true
    }

    /**
     * 独立罗马音行判定：自身没有 roma 字段、没有副行/翻译内容，
     * 且正文是纯拉丁文本（不含 CJK）。
     */
    private fun isLikelyStandaloneRomaLine(line: IRichLyricLine): Boolean {
        if (!line.roma.isNullOrBlank()) return false
        if (!line.secondary.isNullOrBlank()) return false
        if (!line.translation.isNullOrBlank()) return false
        if (!line.secondaryWords.isNullOrEmpty()) return false
        if (!line.translationWords.isNullOrEmpty()) return false
        val text = line.text?.trim().orEmpty()
        if (text.isBlank()) return false
        return containsLatinLetter(text) && !containsCjk(text)
    }

    private fun containsLatinLetter(text: String): Boolean =
        text.any { it in 'a'..'z' || it in 'A'..'Z' }

    private fun containsCjk(text: String): Boolean = text.any { char ->
        val code = char.code
        code in 12352 until 12544 ||
                code in 13312 until 19904 ||
                code in 19968 until 40960
    }

    /** 把罗马音行的正文并入主行的 roma 字段；主行已有 roma 时保持原样。 */
    private fun mergeRomaIntoBase(
        base: IRichLyricLine,
        romaCarrier: IRichLyricLine
    ): IRichLyricLine {
        val romaText = romaCarrier.text?.trim().orEmpty()
        if (romaText.isBlank()) return base
        if (!base.roma.isNullOrBlank()) return base
        return RichLyricLine(
            begin = base.begin,
            end = base.end,
            duration = base.duration,
            isAlignedRight = base.isAlignedRight,
            metadata = base.metadata,
            text = base.text,
            words = base.words,
            secondary = base.secondary,
            secondaryWords = base.secondaryWords,
            translation = base.translation,
            translationWords = base.translationWords,
            roma = romaText
        )
    }

    // ---------------------------------------------------------------------
    // 标题行填充
    // ---------------------------------------------------------------------

    private fun fillGap(song: Song): Song {
        val title = songTitle(song) ?: return song
        val lyrics = song.lyrics?.toMutableList() ?: mutableListOf()
        if (lyrics.isEmpty()) {
            val d = if (song.duration > 0) song.duration else Long.MAX_VALUE
            lyrics.add(titleLine(d, d, title))
        } else {
            val first = lyrics.first()
            if (first.begin > 0) {
                var end = first.begin
                if (end > 1) end-- //优化标题行和第一句歌词同时匹配的抖动
                lyrics.add(0, titleLine(end, end, title))
            }
        }
        song.lyrics = lyrics
        return song
    }

    private fun titleLine(end: Long, duration: Long, text: String) =
        RichLyricLine(end = end, duration = duration, text = text).apply {
            metadata = lyricMetadataOf(KEY_TITLE_LINE to "true")
        }

    private fun songTitle(song: Song): String? {
        val name = song.name
        val artist = song.artist
        return when (placeholder) {
            TitleSlot.NONE -> null
            TitleSlot.NAME_ARTIST -> when {
                !name.isNullOrBlank() && !artist.isNullOrBlank() -> "$name - $artist"
                !name.isNullOrBlank() -> name
                else -> null
            }
            TitleSlot.NAME -> name?.takeIf { it.isNotBlank() }
        }
    }
}

internal class TimedLine(val line: IRichLyricLine) : IRichLyricLine by line {
    var previous: TimedLine? = null
    var next: TimedLine? = null
}
