/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

/*
 * TimingNavigator 性能跑分主程序
 * 运行: gradlew :lyric:model:benchmark
 * 输出: benchmark/results.json (控制台打印精简摘要)
 */
package io.benchmark

import com.sun.management.ThreadMXBean
import io.github.proify.lyricon.lyric.model.LyricLine
import io.github.proify.lyricon.lyric.model.extensions.TimingNavigator
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.lang.management.ManagementFactory
import java.util.Arrays
import java.util.Locale

@Volatile
private var blackhole = 0L

private class TimingBridge(private val nav: TimingNavigator<LyricLine>) : Firstable, ForEachable {
    override fun first(position: Long): LyricLine? = nav.first(position)
    override fun forEachAt(position: Long, action: (LyricLine) -> Unit): Int = nav.forEachAt(position, action)
}

private class TimingBridgeV1(private val nav: TimingNavigatorV1<LyricLine>) : Firstable, ForEachable {
    override fun first(position: Long): LyricLine? = nav.first(position)
    override fun forEachAt(position: Long, action: (LyricLine) -> Unit): Int = nav.forEachAt(position, action)
}

private class Scheme(
    val id: String,
    val name: String,
    val desc: String,
    val hasForEach: Boolean,
    val create: (Array<LyricLine>) -> Firstable,
    val buildNote: String
)

private val schemes = listOf(
    Scheme("linear", "线性扫描", "从头扫描 + 早停 · O(N) 基线, 语义完整", true,
        { LinearNavigator(it) }, "O(1) 辅助内存"),
    Scheme("binary", "纯二分", "定位锚点(最后一条 begin≤pos)后只查锚点 · O(log N), 无重叠处理无缓存", false,
        { BinaryNavigator(it) }, "O(1) 辅助内存"),
    Scheme("cursor", "游标推进", "顺序游标推进 + 二分回退, 用最大时长窗口回溯解决重叠 · 无前缀表", true,
        { CursorNavigator(it) }, "IntArray(4N) 窗口缓冲"),
    Scheme("treemap", "TreeMap 红黑树", "标准库 NavigableMap: floorEntry + lowerEntry 窗口回溯(每步 O(log N))", true,
        { TreeMapNavigator(it) }, "红黑树结点约 80B/条"),
    Scheme("interval", "区间树", "按 begin 折叠构建平衡树, 结点存子树最大结束时间, 查询含点区间", true,
        { IntervalTreeNavigator(it) }, "3 数组共 16N + IntArray(4N)"),
    Scheme("timing-v1", "TimingNavigator v1·优化前", "基线: 缓存二分 + 步进探测 + maxEndSoFar 前缀表(每次重叠解析做完整二分)", true,
        { TimingBridgeV1(TimingNavigatorV1(it)) }, "LongArray(8N) 前缀表"),
    Scheme("timing", "TimingNavigator v2·优化后", "无重叠快路径 + maxEndSoFar O(1) 短路 + 顺序播放滑动 start 缓存 + 指数探测 + 大跳二分门控 + 冗余判断消除", true,
        { TimingBridge(TimingNavigator(it)) }, "LongArray(8N) 前缀表")
)

private class ScenarioDef(
    val id: String,
    val name: String,
    val family: String,
    val desc: String,
    val source: Array<LyricLine>
) {
    val n: Int = source.size
    val songEndMs: Long = source.lastOrNull()?.end ?: 0L
}

private fun buildStd(n: Int) = Array(n) { i ->
    val b = i * 1000L
    LyricLine(begin = b, end = b + 800, text = "L$i")
}

private fun buildOv4(n: Int) = Array(n) { i ->
    val b = i * 1000L
    LyricLine(begin = b, end = b + 4000, text = "L$i")
}

private fun buildOv9(n: Int) = Array(n) { i ->
    val b = i * 1000L
    LyricLine(begin = b, end = b + 9000, text = "L$i")
}

private fun buildUneven(n: Int): Array<LyricLine> {
    val out = arrayOfNulls<LyricLine>(n)
    var state = 0x9E3779B97F4A7C15UL
    var t = 0L
    for (i in 0 until n) {
        state = state * 6364136223846793005UL + 1442695040888963407UL
        val dur = 1000L + (state % 11UL).toLong() * 1000L
        state = state * 6364136223846793005UL + 1442695040888963407UL
        val interval = (dur * 35L / 100L).coerceAtLeast(150L)
        out[i] = LyricLine(begin = t, end = t + dur, text = "L$i")
        t += interval
    }
    @Suppress("UNCHECKED_CAST")
    return out as Array<LyricLine>
}

private val scenarios = listOf(
    ScenarioDef("std-300", "标准 LRC · 300 行", "std", "每行 800ms, 间隔 200ms, 无重叠", buildStd(300)),
    ScenarioDef("std-1200", "标准 LRC · 1.2K 行", "std", "每行 800ms, 间隔 200ms, 无重叠", buildStd(1200)),
    ScenarioDef("std-6000", "标准 LRC · 6K 行", "std", "每行 800ms, 间隔 200ms, 无重叠", buildStd(6000)),
    ScenarioDef("std-60000", "标准 LRC · 60K 行", "std", "每行 800ms, 间隔 200ms, 无重叠", buildStd(60000)),
    ScenarioDef("ov4-6000", "密集重叠 · 6K 行", "overlap", "每行 4s, 1s 一条, 平均 ~4 行并发", buildOv4(6000)),
    ScenarioDef("ov9-6000", "极密重叠 · 6K 行", "overlap", "每行 9s, 1s 一条, 平均 ~9 行并发", buildOv9(6000)),
    ScenarioDef("uneven-6000", "长短句混杂 · 6K 行", "overlap", "时长 1~11s 伪随机, 间距 35%, 并发不规则", buildUneven(6000)),
    ScenarioDef("ov9-60000", "极密重叠 · 60K 行", "overlap", "每行 9s, 1s 一条, 平均 ~9 行并发", buildOv9(60000))
)

private enum class PatternId(val id: String, val label: String, val desc: String) {
    SEQ("seq", "顺序播放", "16ms 步进贯穿整曲, 循环播放"),
    RAND("rand", "随机跳转", "全曲均匀随机定位"),
    SCRUB("scrub", "拖动快进/回退", "±6s 步进随机游走, 10% 大幅跳跃"),
    MIXED("mixed", "顺序+随机混合", "90% 顺序步进 + 10% 随机跳转")
}

private fun buildQueries(sc: ScenarioDef, pattern: PatternId, nOps: Int): LongArray {
    val end = sc.songEndMs
    val arr = LongArray(nOps)
    var state = 0x9E3779B97F4A7C15UL xor nOps.toULong() xor pattern.id.hashCode().toULong()
    fun rnd(max: Long): Long {
        state = state * 6364136223846793005UL + 1442695040888963407UL
        return Math.floorMod((state shr 1).toLong(), max)
    }
    var pos = 0L
    when (pattern) {
        PatternId.SEQ -> {
            // 4 个连续播放窗口(0/25/50/75%), 窗口内 16ms 步进, 覆盖整曲而非仅开头
            val segs = 4
            val per = nOps / segs
            val span = (end + 1L) / segs
            var k = 0
            for (s in 0 until segs) {
                val base = span * s
                val cnt = if (s == segs - 1) nOps - per * (segs - 1) else per
                for (j in 0 until cnt) { arr[k++] = Math.min(base + j * 16L, end) }
            }
        }
        PatternId.RAND -> {
            for (k in 0 until nOps) arr[k] = rnd(end + 1L)
        }
        PatternId.SCRUB -> {
            pos = rnd(end + 1L)
            for (k in 0 until nOps) {
                val r = rnd(100L)
                pos = if (r < 10) rnd(end + 1L) else (pos + rnd(8000L) - 2000L).coerceIn(0L, end)
                arr[k] = pos
            }
        }
        PatternId.MIXED -> {
            for (k in 0 until nOps) {
                val r = rnd(100L)
                pos = if (r < 10L) rnd(end + 1L) else (pos + 16L) % (end + 1L)
                arr[k] = pos
            }
        }
    }
    return arr
}

private enum class OpsMode { FIRST, FOR_EACH }
private const val WARM_BATCHES = 12
private const val MEASURE_BATCHES = 21
private const val N_LOOP_LIMIT = 40_000_000L

private fun nOpsFor(n: Int): Int = (N_LOOP_LIMIT / n).coerceIn(2_000L, 120_000L).toInt()

private class Operand { var v = 0L }

private class Timings(val nsMedian: Double, val nsP90: Double, val nsMin: Double, val allocPerOp: Double)

private fun runFirstBatch(instance: Firstable, queries: LongArray): Long {
    var cs = 0L
    var i = 0
    val n = queries.size
    while (i < n) {
        val q = queries[i]
        val r = instance.first(q)
        cs = cs * 31 + (r?.begin ?: -1L)
        i++
    }
    return cs
}

private fun measureCell(
    scheme: Scheme, sc: ScenarioDef, pattern: PatternId, queries: LongArray, mode: OpsMode
): Timings {
    val instance = scheme.create(sc.source)
    val ops = queries.size
    val h = Operand()
    val action: (LyricLine) -> Unit = { h.v = h.v * 31 + it.begin }

    fun runOnce(): Long {
        return if (mode == OpsMode.FIRST) runFirstBatch(instance, queries)
        else {
            val fe = instance as ForEachable
            var i = 0
            while (i < ops) { fe.forEachAt(queries[i], action); i++ }
            h.v
        }
    }

    var cs = 0L
    repeat(WARM_BATCHES) { cs = cs xor runOnce() }
    blackhole = blackhole xor cs

    val tmx = (ManagementFactory.getThreadMXBean() as? ThreadMXBean)
        ?.takeIf { it.isThreadAllocatedMemorySupported }
    val tid = Thread.currentThread().id
    val samples = LongArray(MEASURE_BATCHES)
    val allocs = LongArray(MEASURE_BATCHES)
    var acc = 0L
    for (i in 0 until MEASURE_BATCHES) {
        val b0 = if (tmx != null) tmx.getThreadAllocatedBytes(tid) else 0L
        val t0 = System.nanoTime()
        val c = runOnce()
        val t1 = System.nanoTime()
        val b1 = if (tmx != null) tmx.getThreadAllocatedBytes(tid) else 0L
        samples[i] = t1 - t0
        allocs[i] = (b1 - b0).coerceAtLeast(0L)
        acc = acc xor c
    }
    blackhole = blackhole xor acc
    Arrays.sort(samples)
    Arrays.sort(allocs)
    val med = samples[samples.size / 2].toDouble() / ops
    val p90 = samples[(samples.size * 9 / 10).coerceAtMost(samples.size - 1)].toDouble() / ops
    val min = samples[0].toDouble() / ops
    val alloc = allocs[allocs.size / 2].toDouble() / ops
    return Timings(med, p90, min, alloc)
}

// ---- 正确性 ----

private fun upperBoundIdx(src: Array<LyricLine>, position: Long): Int {
    var low = 0
    var high = src.size - 1
    var ans = -1
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (src[mid].begin <= position) { ans = mid; low = mid + 1 } else high = mid - 1
    }
    return ans
}

private class GroundTruth(
    val positions: LongArray,
    val earliest: IntArray,
    val anchor: IntArray,
    val activeCount: IntArray
)

private fun buildGroundTruth(sc: ScenarioDef, probes: Int = 1600): GroundTruth {
    val src = sc.source
    val n = src.size
    val end = sc.songEndMs
    var state = 0x9E3779B97F4A7C15UL
    fun rnd(max: Long): Long {
        state = state * 6364136223846793005UL + 1442695040888963407UL
        return Math.floorMod((state shr 1).toLong(), max)
    }
    val posList = ArrayList<Long>(probes + 400)
    val step = (n / 240).coerceAtLeast(1)
    var i = 0
    while (i < n && posList.size < probes - 400) {
        val e = src[i]
        posList.add(e.begin)
        posList.add((e.begin + e.end) / 2)
        posList.add(e.end)
        posList.add(e.end + 1)
        posList.add((e.begin - 1).coerceAtLeast(0))
        i += step
    }
    while (posList.size < probes) posList.add(rnd(end + 1L))
    val positions = posList.distinct().sorted().toLongArray()
    val earliest = IntArray(positions.size)
    val anchor = IntArray(positions.size)
    val activeCount = IntArray(positions.size)
    for (k in positions.indices) {
        val pos = positions[k]
        var best = -1
        var cnt = 0
        for (j in 0 until n) {
            val e = src[j]
            if (e.begin > pos) break
            if (pos <= e.end) { cnt++; if (best < 0) best = j }
        }
        earliest[k] = best
        activeCount[k] = cnt
        val a = upperBoundIdx(src, pos)
        anchor[k] = if (a >= 0 && pos <= src[a].end) a else best
    }
    return GroundTruth(positions, earliest, anchor, activeCount)
}

private fun indexOfBegin(src: Array<LyricLine>, begin: Long): Int {
    var lo = 0
    var hi = src.size - 1
    var ans = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (src[mid].begin <= begin) { ans = mid; lo = mid + 1 } else hi = mid - 1
    }
    return ans
}

private fun correctnessFirst(scheme: Scheme, sc: ScenarioDef, gt: GroundTruth): Pair<Double, Double> {
    val inst = scheme.create(sc.source)
    var early = 0
    var anch = 0
    val total = gt.positions.size
    // 双向各扫一遍: 正向验证单调缓存, 逆向暴露回退/跳转缓存错误
    for (pass in 0..1) {
        for (k in 0 until total) {
            val q = if (pass == 0) k else total - 1 - k
            val r = inst.first(gt.positions[q])
            val idx = if (r != null) indexOfBegin(sc.source, r.begin) else -1
            if (idx == gt.earliest[q]) early++
            if (idx == gt.anchor[q]) anch++
        }
    }
    return Pair(early.toDouble() / (total * 2), anch.toDouble() / (total * 2))
}

private fun correctnessForEach(scheme: Scheme, sc: ScenarioDef, gt: GroundTruth): Double {
    val inst = scheme.create(sc.source) as ForEachable
    val got = ArrayList<LyricLine>(32)
    var ok = 0
    val total = gt.positions.size
    var checks = 0
    // 双向各扫一遍: 正向验证单调缓存, 逆向暴露回退/跳转缓存错误
    for (pass in 0..1) {
        for (k in 0 until total) {
            val q = if (pass == 0) k else total - 1 - k
            val pos = gt.positions[q]
            got.clear()
            inst.forEachAt(pos) { got.add(it) }
            var same = true
            var cnt = 0
            for (j in 0 until sc.source.size) {
                val e = sc.source[j]
                if (e.begin > pos) break
                if (pos <= e.end) {
                    if (cnt < got.size && got[cnt].begin == e.begin) cnt++ else { same = false; break }
                }
            }
            if (cnt != got.size) same = false
            if (same) ok++
            checks++
        }
    }
    return ok.toDouble() / checks
}

// ---- 构建成本 ----

private fun measureBuild(scheme: Scheme, src: Array<LyricLine>, rounds: Int = 7): Double {
    val samples = LongArray(rounds)
    var cs = 0L
    for (i in 0 until rounds) {
        val t0 = System.nanoTime()
        val inst = scheme.create(src)
        val t1 = System.nanoTime()
        samples[i] = t1 - t0
        cs = cs xor (inst.first(0L)?.begin ?: 0L)
    }
    blackhole = blackhole xor cs
    Arrays.sort(samples)
    return samples[rounds / 2].toDouble()
}

private fun approxExtraBytes(schemeId: String, n: Int): Long = when (schemeId) {
    "timing" -> n * 8L + 16
    "cursor" -> n * 4L + 16
    "interval" -> n * 16L + 16
    "treemap" -> n * 80L
    else -> 0L
}

private fun computeConcurrency(sc: ScenarioDef): Triple<Double, Int, Double> {
    var sum = 0L
    var max = 0
    var samples = 0
    var twoPlus = 0
    val step = (sc.songEndMs / 2000L).coerceAtLeast(1L)
    var pos = 0L
    while (pos <= sc.songEndMs) {
        var c = 0
        for (j in 0 until sc.n) {
            val e = sc.source[j]
            if (e.begin > pos) break
            if (pos <= e.end) c++
        }
        sum += c
        if (c > max) max = c
        if (c >= 2) twoPlus++
        samples++
        pos += step * 7L
    }
    val avg = if (samples > 0) sum.toDouble() / samples else 0.0
    return Triple(avg, max, if (samples > 0) twoPlus.toDouble() / samples else 0.0)
}

private class FirstResult(
    val ops: Int,
    val timings: Timings,
    val correctEarliest: Double,
    val correctAnchor: Double
)

private class ForEachResult(val ops: Int, val timings: Timings, val correct: Double)

private class BuildResult(val ns: Double, val extraBytes: Long)

fun main(args: Array<String>) {
    // 快速重生成模式: --regen 直接读取 results.json + report-template.html 产出 index.html
    if (args.isNotEmpty() && args[0] == "--regen") {
        val outDir = File("benchmark")
        val json = File(outDir, "results.json").readText(Charsets.UTF_8)
        val tplFile = File(outDir, "report-template.html")
        if (!tplFile.exists()) {
            println("report-template.html not found")
            return
        }
        val html = tplFile.readText(Charsets.UTF_8)
            .replace("__DATA__", json.replace("</script", "<\\/script"))
        val outFile = File(outDir, "index.html")
        outFile.writeText(html, Charsets.UTF_8)
        println("regen -> " + outFile.absolutePath)
        return
    }
    Locale.setDefault(Locale.US)
    val outDir = File(if (args.isNotEmpty()) args[0] else "benchmark")
    outDir.mkdirs()
    val outFile = File(outDir, "results.json")

    val cpu = System.getenv("PROCESSOR_IDENTIFIER") ?: "unknown"
    println("=== TimingNavigator Performance Benchmark ===")
    println("JVM: ${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}")
    println("CPU: ${cpu}  cores=${Runtime.getRuntime().availableProcessors()}")
    println("scenarios=${scenarios.size} schemess=${schemes.size} warm=${WARM_BATCHES} measure=${MEASURE_BATCHES}")

    // 场景元数据
    val scenData = LinkedHashMap<String, Triple<Double, Int, Double>>()
    for (sc in scenarios) scenData[sc.id] = computeConcurrency(sc)

    // ==== 测量: 完整跑 3 轮, 汇总取轮次中位数(压制单轮噪声) ====
    val ROUNDS = 3
    fun med3(v: List<Double>): Double {
        val s = v.sorted()
        return s[s.size / 2]
    }
    val buildSrcs = listOf(
        "std-60000" to buildStd(60000),
        "ov9-60000" to buildOv9(60000)
    )
    println("\n--- rounds=${ROUNDS} ---")
    val firstRaw = LinkedHashMap<String, LinkedHashMap<String, MutableList<FirstResult>>>()
    val forEachRaw = LinkedHashMap<String, LinkedHashMap<String, MutableList<ForEachResult>>>()
    val buildRaw = LinkedHashMap<String, LinkedHashMap<String, MutableList<BuildResult>>>()

    for (round in 0 until ROUNDS) {
        System.gc()
        // 构建成本
        for ((sid, src) in buildSrcs) {
            for (sch in schemes) {
                val ns = measureBuild(sch, src)
                buildRaw.getOrPut(sid) { LinkedHashMap() }
                    .getOrPut(sch.id) { mutableListOf() }
                    .add(BuildResult(ns, approxExtraBytes(sch.id, src.size)))
            }
        }
        // first 模式
        for (sc in scenarios) {
            for (pat in PatternId.entries) {
                val nOps = nOpsFor(sc.n)
                val queries = buildQueries(sc, pat, nOps)
                val gt = buildGroundTruth(sc)
                for (sch in schemes) {
                    val t = measureCell(sch, sc, pat, queries, OpsMode.FIRST)
                    val (ce, ca) = correctnessFirst(sch, sc, gt)
                    firstRaw.getOrPut("${sc.id}/${pat.id}") { LinkedHashMap() }
                        .getOrPut(sch.id) { mutableListOf() }
                        .add(FirstResult(nOps, t, ce, ca))
                }
            }
        }
        // forEachAt 模式
        val feScenarios = scenarios.filter { it.id in setOf("ov4-6000", "ov9-6000", "uneven-6000", "std-6000") }
        for (sc in feScenarios) {
            for (pat in listOf(PatternId.SEQ, PatternId.RAND, PatternId.MIXED)) {
                val nOps = nOpsFor(sc.n)
                val queries = buildQueries(sc, pat, nOps)
                val gt = buildGroundTruth(sc)
                for (sch in schemes.filter { it.id != "binary" }) {
                    val t = measureCell(sch, sc, pat, queries, OpsMode.FOR_EACH)
                    val ok = correctnessForEach(sch, sc, gt)
                    forEachRaw.getOrPut("${sc.id}/${pat.id}") { LinkedHashMap() }
                        .getOrPut(sch.id) { mutableListOf() }
                        .add(ForEachResult(nOps, t, ok))
                }
            }
        }
        println("  round ${round + 1}/${ROUNDS} done")
    }

    // ==== 汇总: 各字段取轮次中位数 ====
    val buildResults = LinkedHashMap<String, LinkedHashMap<String, BuildResult>>()
    for ((sid, m) in buildRaw) {
        val per = LinkedHashMap<String, BuildResult>()
        for ((schId, list) in m) {
            per[schId] = BuildResult(
                med3(list.map { it.ns }),
                med3(list.map { it.extraBytes.toDouble() }).toLong()
            )
        }
        buildResults[sid] = per
        println("  build ${sid}: " + per.entries.joinToString("  ") { "${it.key}:${"%.2f".format(it.value.ns / 1e6)}ms" })
    }

    val firstResults = LinkedHashMap<String, LinkedHashMap<String, FirstResult>>()
    for ((key, m) in firstRaw) {
        val per = LinkedHashMap<String, FirstResult>()
        for ((schId, list) in m) {
            val ops = list[0].ops
            per[schId] = FirstResult(
                ops,
                Timings(
                    med3(list.map { it.timings.nsMedian }),
                    med3(list.map { it.timings.nsP90 }),
                    med3(list.map { it.timings.nsMin }),
                    med3(list.map { it.timings.allocPerOp })
                ),
                med3(list.map { it.correctEarliest }),
                med3(list.map { it.correctAnchor })
            )
        }
        firstResults[key] = per
        val tv = per["timing"]
        println("  first ${key}: v1=${"%.1f".format(per["timing-v1"]?.timings?.nsMedian ?: 0.0)} v2=${"%.1f".format(tv?.timings?.nsMedian ?: 0.0)} ns/op  correct=${"%.1f%%".format((tv?.correctEarliest ?: 0.0) * 100)}")
    }

    val forEachResults = LinkedHashMap<String, LinkedHashMap<String, ForEachResult>>()
    for ((key, m) in forEachRaw) {
        val per = LinkedHashMap<String, ForEachResult>()
        for ((schId, list) in m) {
            per[schId] = ForEachResult(
                list[0].ops,
                Timings(
                    med3(list.map { it.timings.nsMedian }),
                    med3(list.map { it.timings.nsP90 }),
                    med3(list.map { it.timings.nsMin }),
                    med3(list.map { it.timings.allocPerOp })
                ),
                med3(list.map { it.correct })
            )
        }
        forEachResults[key] = per
        println("  fe ${key}: v1=${"%.1f".format(per["timing-v1"]?.timings?.nsMedian ?: 0.0)} v2=${"%.1f".format(per["timing"]?.timings?.nsMedian ?: 0.0)} ns/op  correct=${"%.1f%%".format((per["timing"]?.correct ?: 0.0) * 100)}")
    }

    // 组装 JSON
    val json = buildJsonObject {
        put("meta", buildJsonObject {
            put("title", "TimingNavigator 性能跑分报告")
            put("generatedAt", System.currentTimeMillis())
            put("jvm", "${System.getProperty("java.vm.name")} ${System.getProperty("java.version")} (${System.getProperty("java.vm.version")})")
            put("os", "${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}")
            put("cpu", cpu)
            put("cores", Runtime.getRuntime().availableProcessors())
            put("warmBatches", WARM_BATCHES)
            put("measureBatches", MEASURE_BATCHES)
        })
        put("scenarios", buildJsonObject {
            for (sc in scenarios) putJsonObject(sc.id) {
                val (avg, mxx, ratio) = scenData[sc.id]!!
                put("name", sc.name)
                put("family", sc.family)
                put("desc", sc.desc)
                put("n", sc.n)
                put("songEndMs", sc.songEndMs)
                put("avgConcurrent", avg)
                put("maxConcurrent", mxx)
                put("overlapRatio", ratio)
            }
        })
        put("patterns", buildJsonObject {
            for (p in PatternId.entries) putJsonObject(p.id) {
                put("name", p.label)
                put("desc", p.desc)
            }
        })
        put("schemes", buildJsonObject {
            for (s in schemes) putJsonObject(s.id) {
                put("name", s.name)
                put("desc", s.desc)
                put("hasForEach", s.hasForEach)
            }
        })
        put("firstCells", buildJsonObject {
            for ((key, per) in firstResults) putJsonObject(key) {
                for ((sid, r) in per) putJsonObject(sid) {
                    put("ops", r.ops)
                    put("nsMedian", r.timings.nsMedian)
                    put("nsP90", r.timings.nsP90)
                    put("nsMin", r.timings.nsMin)
                    put("allocBPerOp", r.timings.allocPerOp)
                    put("correctEarliest", r.correctEarliest)
                    put("correctAnchor", r.correctAnchor)
                }
            }
        })
        put("forEachCells", buildJsonObject {
            for ((key, per) in forEachResults) putJsonObject(key) {
                for ((sid, r) in per) putJsonObject(sid) {
                    put("ops", r.ops)
                    put("nsMedian", r.timings.nsMedian)
                    put("nsP90", r.timings.nsP90)
                    put("nsMin", r.timings.nsMin)
                    put("allocBPerOp", r.timings.allocPerOp)
                    put("correct", r.correct)
                }
            }
        })
        put("buildCells", buildJsonObject {
            for ((key, per) in buildResults) putJsonObject(key) {
                for ((sid, r) in per) putJsonObject(sid) {
                    put("buildNs", r.ns)
                    put("extraBytesApprox", r.extraBytes)
                    put("note", schemes.first { it.id == sid }.buildNote)
                }
            }
        })
    }

    outFile.writeText(json.toString(), Charsets.UTF_8)

    // 生成内嵌 JSON 的自包含跑分报告(需模板存在)
    val tplFile = File(outDir, "report-template.html")
    if (tplFile.exists()) {
        val html = tplFile.readText(Charsets.UTF_8)
            .replace("__DATA__", json.toString().replace("</script", "<\\/script"))
        val reportFile = File(outDir, "index.html")
        reportFile.writeText(html, Charsets.UTF_8)
        println("report -> ${reportFile.absolutePath}")
    } else {
        println("report-template.html not found, skipped report generation")
    }
    println("\nDone. results -> ${outFile.absolutePath}")
    println("blackhole=${blackhole}")
}