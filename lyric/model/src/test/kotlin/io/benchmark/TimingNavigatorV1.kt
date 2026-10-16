/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

/*
 * TimingNavigator v1 基线副本 — 仅用于跑分对比(源码见主库 TimingNavigator.kt 的历史版本)
 */
package io.benchmark

import io.github.proify.lyricon.lyric.model.interfaces.ILyricTiming

class TimingNavigatorV1<T : ILyricTiming>(
    val source: Array<T>
) {
    val size: Int = source.size
    val maxEndSoFar: LongArray = LongArray(size)

    init {
        var currentMax = -1L
        for (i in source.indices) {
            val end = source[i].end
            if (end > currentMax) {
                currentMax = end
            }
            maxEndSoFar[i] = currentMax
        }
    }

    var lastMatchedIndex: Int = -1
        private set
    var lastQueryPosition: Long = -1L
        private set

    fun first(position: Long): T? {
        val index = findTargetIndex(position)
        updateCache(position, index)
        if (index == -1) return null
        if (position <= source[index].end) {
            return source[index]
        }
        resolveOverlapping(position, index) { return it }
        return null
    }

    inline fun forEachAt(position: Long, action: (T) -> Unit): Int {
        if (size == 0) return 0
        val anchorIndex = findTargetIndex(position)
        updateCache(position, anchorIndex)
        if (anchorIndex == -1) return 0
        return resolveOverlapping(position, anchorIndex, action)
    }

    inline fun forEachAtOrPrevious(position: Long, action: (T) -> Unit): Int {
        val count = forEachAt(position, action)
        if (count > 0) return count
        val previous = findPreviousEntry(position) ?: return 0
        action(previous)
        return 1
    }

    fun findPreviousEntry(position: Long): T? {
        val idx = findUpperBound(position)
        return if (idx >= 0) source[idx] else null
    }

    @Suppress("unused")
    fun resetCache() {
        lastMatchedIndex = -1
        lastQueryPosition = -1L
    }

    fun findTargetIndex(position: Long): Int {
        if (size == 0 || position < source[0].begin) return -1
        val lastIdx = lastMatchedIndex
        if (lastIdx >= 0 && position >= lastQueryPosition && position >= source[lastIdx].begin) {
            var currIdx = lastIdx
            var steps = 0
            while (currIdx + 1 < size && source[currIdx + 1].begin <= position) {
                currIdx++
                steps++
                if (steps > 4) return findUpperBound(position)
            }
            return currIdx
        }
        return findUpperBound(position)
    }

    private fun findUpperBound(position: Long): Int {
        var low = 0
        var high = size - 1
        var ans = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (source[mid].begin <= position) {
                ans = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return ans
    }

    @PublishedApi
    internal inline fun resolveOverlapping(
        position: Long,
        anchorIndex: Int,
        action: (T) -> Unit
    ): Int {
        if (anchorIndex < 0) return 0
        var low = 0
        var high = anchorIndex
        var start = anchorIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (maxEndSoFar[mid] >= position) {
                start = mid
                high = mid - 1
            } else {
                low = mid + 1
            }
        }
        var count = 0
        for (i in start..anchorIndex) {
            val entry = source[i]
            if (position <= entry.end && position >= entry.begin) {
                action(entry)
                count++
            }
        }
        return count
    }

    @PublishedApi
    internal fun updateCache(position: Long, index: Int) {
        lastQueryPosition = position
        lastMatchedIndex = index
    }
}
