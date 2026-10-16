/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

/*
 * 横向对比方案集合 — TimingNavigator 性能跑分
 */
package io.benchmark

import io.github.proify.lyricon.lyric.model.LyricLine
import java.util.TreeMap

/**
 * 统一语义接口(全部方案走同一接口派发, 保证对比公平)
 * first(pos): 返回位置 pos 处 "按 begin 升序第一个活动行" (最早开始)
 * forEachAt: 按 begin 升序回调所有活动行
 */
interface Firstable { fun first(position: Long): LyricLine? }
interface ForEachable { fun forEachAt(position: Long, action: (LyricLine) -> Unit): Int }

/** 方案 A: 线性扫描(带早停) — 最朴素基线,语义完整 */
class LinearNavigator(private val source: Array<LyricLine>) : Firstable, ForEachable {
    private val size = source.size
    override fun first(position: Long): LyricLine? {
        for (i in 0 until size) {
            val e = source[i]
            if (e.begin > position) break
            if (position <= e.end) return e
        }
        return null
    }
    override fun forEachAt(position: Long, action: (LyricLine) -> Unit): Int {
        var count = 0
        for (i in 0 until size) {
            val e = source[i]
            if (e.begin > position) break
            if (position <= e.end) { action(e); count++ }
        }
        return count
    }
}

/** 方案 B: 纯二分定位锚点(最后一条 begin<=pos), 仅检查锚点 — 无重叠处理、无缓存 */
class BinaryNavigator(private val source: Array<LyricLine>) : Firstable {
    private val size = source.size
    override fun first(position: Long): LyricLine? {
        if (size == 0 || position < source[0].begin) return null
        var low = 0
        var high = size - 1
        var ans = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (source[mid].begin <= position) { ans = mid; low = mid + 1 } else high = mid - 1
        }
        // 重叠场景: 锚点已结束但更早的行仍活动时返回 null(语义不完整, 报告中将如实标注)
        return if (ans >= 0 && position <= source[ans].end) source[ans] else null
    }
}

/** 方案 C: 顺序游标推进 + 大跳/回退二分; 重叠解决用 "最大时长窗口" 回溯扫描, 无前缀最大结束时间表 */
class CursorNavigator(private val source: Array<LyricLine>) : Firstable, ForEachable {
    private val size = source.size
    private val maxDur: Long
    private var cursor = -1
    private var lastPosition = Long.MIN_VALUE
    private val activeBuffer = IntArray(size)

    init {
        var m = 0L
        for (i in 0 until size) {
            val e = source[i]
            val d = e.end - e.begin
            if (d > m) m = d
        }
        maxDur = m
    }

    private fun upperBound(position: Long): Int {
        var low = 0
        var high = size - 1
        var ans = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (source[mid].begin <= position) { ans = mid; low = mid + 1 } else high = mid - 1
        }
        return ans
    }

    private fun locate(position: Long) {
        if (cursor >= 0 && position >= source[cursor].begin) {
            if (position - lastPosition > 32_768) {
                cursor = upperBound(position)
            } else {
                var steps = 0
                while (cursor + 1 < size && source[cursor + 1].begin <= position) {
                    cursor++
                    if (++steps > 8) { cursor = upperBound(position); break }
                }
            }
        } else {
            cursor = upperBound(position)
        }
        lastPosition = position
    }

    override fun first(position: Long): LyricLine? {
        if (size == 0 || position < source[0].begin) {
            cursor = -1; lastPosition = position
            return null
        }
        locate(position)
        if (cursor < 0) return null
        // 窗口回溯: 活动行 begin 必在 [position - maxDur, position]
        val limit = position - maxDur
        var best = -1
        var i = cursor
        while (i >= 0 && source[i].begin >= limit) {
            val e = source[i]
            if (position <= e.end) best = i
            i--
        }
        return if (best >= 0) source[best] else null
    }

    override fun forEachAt(position: Long, action: (LyricLine) -> Unit): Int {
        if (size == 0 || position < source[0].begin) {
            cursor = -1; lastPosition = position
            return 0
        }
        locate(position)
        if (cursor < 0) return 0
        val limit = position - maxDur
        var n = 0
        var i = cursor
        while (i >= 0 && source[i].begin >= limit) {
            val e = source[i]
            if (position <= e.end) activeBuffer[n++] = i
            i--
        }
        var count = 0
        for (k in n - 1 downTo 0) { action(source[activeBuffer[k]]); count++ }
        return count
    }
}

/** 方案 D: 标准库 TreeMap 红黑树 — floorEntry + lowerEntry 窗口回溯(每次回溯 O(log N)) */
class TreeMapNavigator(private val source: Array<LyricLine>) : Firstable, ForEachable {
    private val tree = TreeMap<Long, LyricLine>()
    private val maxDur: Long
    private val activeBuffer = ArrayList<LyricLine>(16)

    init {
        var m = 0L
        for (i in source.indices) {
            val e = source[i]
            tree.put(e.begin, e)
            val d = e.end - e.begin
            if (d > m) m = d
        }
        maxDur = m
    }

    override fun first(position: Long): LyricLine? {
        val limit = position - maxDur
        var best: LyricLine? = null
        var e = tree.floorEntry(position)
        while (e != null && e.key >= limit) {
            val v = e.value
            if (position <= v.end) best = v
            e = tree.lowerEntry(e.key)
        }
        return best
    }

    override fun forEachAt(position: Long, action: (LyricLine) -> Unit): Int {
        val limit = position - maxDur
        activeBuffer.clear()
        var e = tree.floorEntry(position)
        while (e != null && e.key >= limit) {
            val v = e.value
            if (position <= v.end) activeBuffer.add(v)
            e = tree.lowerEntry(e.key)
        }
        var count = 0
        for (k in activeBuffer.indices.reversed()) { action(activeBuffer[k]); count++ }
        return count
    }
}

/** 方案 E: 平衡区间树 — 按 begin 折叠构建, 每结点记录子树最大结束时间, 查询含点区间 O(log N + 访问) */
class IntervalTreeNavigator(private val source: Array<LyricLine>) : Firstable, ForEachable {
    private val size = source.size
    private val left = IntArray(size) { -1 }
    private val right = IntArray(size) { -1 }
    private val subMaxEnd = LongArray(size)
    private val buffer = IntArray(size)
    private val root: Int

    init {
        root = if (size == 0) -1 else build(0, size)
    }

    private fun build(l: Int, r: Int): Int {
        if (l >= r) return -1
        val mid = (l + r) ushr 1
        val li = build(l, mid)
        val ri = build(mid + 1, r)
        left[mid] = li
        right[mid] = ri
        var m = source[mid].end
        if (li >= 0 && subMaxEnd[li] > m) m = subMaxEnd[li]
        if (ri >= 0 && subMaxEnd[ri] > m) m = subMaxEnd[ri]
        subMaxEnd[mid] = m
        return mid
    }

    private fun query(node: Int, position: Long, count: Int): Int {
        if (node < 0 || subMaxEnd[node] < position) return count
        val e = source[node]
        var c = count
        if (e.begin <= position && position <= e.end) { buffer[c] = node; c++ }
        if (e.begin <= position) c = query(right[node], position, c)
        return query(left[node], position, c)
    }

    override fun first(position: Long): LyricLine? {
        if (size == 0) return null
        val n = query(root, position, 0)
        if (n == 0) return null
        java.util.Arrays.sort(buffer, 0, n)
        return source[buffer[0]]
    }

    override fun forEachAt(position: Long, action: (LyricLine) -> Unit): Int {
        if (size == 0) return 0
        val n = query(root, position, 0)
        java.util.Arrays.sort(buffer, 0, n)
        for (k in 0 until n) action(source[buffer[k]])
        return n
    }
}
