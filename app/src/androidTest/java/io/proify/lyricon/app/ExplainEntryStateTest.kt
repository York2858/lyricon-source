package io.github.proify.lyricon.app.activity.lyric

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import io.github.proify.lyricon.app.ai.explain.*
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Saved local fixtures; resolving a configured AI endpoint is forbidden in these tests. */
class ExplainEntryStateTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as Application
    private val history get() = ExplainHistory(app)
    private var state: ExplainViewModel? = null
    private val savedIds = mutableSetOf<String>()
    private val origin = System.currentTimeMillis() + 10000
    private val song = ExplainSong("入口测试-${UUID.randomUUID()}", "入口艺人", "入口专辑", listOf(
        ExplainLine("重复歌词", 0, 1000), ExplainLine("重复歌词", 2000, 3000)))
    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private suspend fun await(predicate: (ExplainViewModel) -> Boolean) = withTimeout(5000) {
        while (true) {
            var ready = false
            onMain { ready = state?.let(predicate) == true }
            if (ready) break
            delay(20)
        }
    }
    private suspend fun save(range: ExplainRange, index: Int, content: String, time: Long,
        angle: ExplainAngle = if (range == ExplainRange.SONG) ExplainAngle.OVERVIEW else ExplainAngle.PHRASE): ExplainRecord {
        val value = ExplainRecord(UUID.randomUUID().toString(), song, range, angle, index,
            listOf(ExplainTurn(UUID.randomUUID().toString(), content = content, status = ExplainStatus.COMPLETE)), origin + time)
        history.put(value); savedIds += value.id
        return value
    }
    @After fun clear() = runBlocking {
        onMain { state?.stop(); state?.viewModelScope?.cancel() }
        history.delete(savedIds)
        Unit
    }

    @Test fun newEntryDefaultsToWholeSongEvenWhenTheClickFallsOnALine() = runBlocking {
        var resolutions = 0
        onMain { state = ExplainViewModel(app, song, 2500, null) { resolutions++; null } }
        await { it.error != null }
        onMain {
            assertEquals(ExplainRange.SONG, state!!.record.range)
            assertEquals(ExplainAngle.OVERVIEW, state!!.record.angle)
            assertEquals(1, resolutions)
        }
    }

    @Test fun newestWholeSongIsShownWithoutResolvingAiAndLineRemainsTheClickedOccurrence() = runBlocking {
        save(ExplainRange.SONG, 0, "旧赏析", 1)
        val latest = save(ExplainRange.SONG, 0, "最近的整首解读", 3, ExplainAngle.IMAGERY)
        save(ExplainRange.LINE, 0, "第一次重复", 5)
        val clicked = save(ExplainRange.LINE, 1, "第二次重复", 4)
        var resolutions = 0
        onMain { state = ExplainViewModel(app, song, 2500, null) { resolutions++; null } }
        await { it.record.id == latest.id }
        onMain {
            assertEquals(ExplainRange.SONG, state!!.record.range)
            assertEquals(0, resolutions)
            state!!.setRange(ExplainRange.LINE)
        }
        await { it.record.id == clicked.id }
        onMain {
            assertEquals(1, state!!.record.lineIndex)
            assertEquals("第二次重复", state!!.record.turns.single().content)
            assertEquals(0, resolutions)
            state!!.setRange(ExplainRange.SONG)
        }
        await { it.record.id == latest.id }
        onMain { assertEquals(0, resolutions) }
    }

    @Test fun savedWholeSongRemainsReadableWhenTheSnapshotHasNoLyrics() = runBlocking {
        val latest = save(ExplainRange.SONG, 0, "已有记录", 1)
        var resolutions = 0
        onMain { state = ExplainViewModel(app, song.copy(lines = emptyList()), 0, null) { resolutions++; null } }
        await { it.record.id == latest.id }
        onMain { assertEquals(0, resolutions); assertNull(state!!.error) }
    }

    @Test fun explicitlyOpenedHistoryIsNotReplacedByPendingStartup() = runBlocking {
        save(ExplainRange.SONG, 0, "启动记录", 2)
        val explicit = save(ExplainRange.LINE, 1, "手动打开", 1)
        var resolutions = 0
        onMain {
            state = ExplainViewModel(app, song, 500, null) { resolutions++; null }
            state!!.openHistory(explicit)
        }
        await { it.records.any { record -> record.id == explicit.id } }
        onMain { assertEquals(explicit.id, state!!.record.id); assertEquals(0, resolutions) }
    }
}
