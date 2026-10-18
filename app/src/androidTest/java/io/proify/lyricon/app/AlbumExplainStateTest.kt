package io.github.proify.lyricon.app.activity.lyric

import android.app.Application
import android.net.Uri
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import io.github.proify.lyricon.app.ai.explain.*
import io.github.proify.lyricon.lyric.ai.core.AiChatStreamResult
import io.github.proify.lyricon.lyric.ai.core.AiConfig
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Deterministic local streams: no API requests, credentials, or production history. */
class AlbumExplainStateTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as Application
    private var state: AlbumExplainViewModel? = null
    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
    @After fun clear() { onMain { state?.stop(); state?.viewModelScope?.cancel() } }
    private fun prepare(stream: AlbumExplainStream) = onMain {
        state = AlbumExplainViewModel(app, configProvider = { AiConfig("fixture", UUID.randomUUID().toString(), "https://example.test") }, streamer = stream)
        state!!.albumTitle = "专辑测试-${UUID.randomUUID()}"
        state!!.artist = "测试艺人"
        state!!.trackList = "3.Holy, Holy\n4.The New Sound"
        assertTrue(state!!.applyTracks())
    }
    private suspend fun await(predicate: (AlbumExplainViewModel) -> Boolean) = withTimeout(10000) {
        while (true) {
            var ready = false
            onMain { ready = state?.let(predicate) == true }
            if (ready) break
            delay(25)
        }
    }

    @Test fun sequentialFailurePreservesFirstTrackAndResumeSkipsIt() = runBlocking {
        val requested = mutableListOf<String>()
        var fail = true
        prepare { _, _, user, _, content ->
            requested += user
            if (user.contains("本次歌曲：4.") && fail) { content("保留未完成的编曲分析"); null }
            else AiChatStreamResult("", "歌词与表达：测试。编曲与律动：测试。")
        }
        onMain { state!!.generate() }
        await { !it.working && it.record!!.tracks[1].status == ExplainStatus.ERROR }
        onMain {
            assertEquals(ExplainStatus.COMPLETE, state!!.record!!.tracks[0].status)
            assertEquals("保留未完成的编曲分析", state!!.record!!.tracks[1].content)
            fail = false; state!!.generate()
        }
        await { !it.working && it.record!!.tracks.all { track -> track.status == ExplainStatus.COMPLETE } }
        assertEquals(3, requested.size)
        assertTrue(requested[0].contains("本次歌曲：3. Holy, Holy"))
        assertTrue(requested[1].contains("本次歌曲：4. The New Sound"))
        assertTrue(requested[2].contains("本次歌曲：4. The New Sound"))
        await { it.records.any { saved -> saved.id == it.record!!.id && saved.tracks.all { track -> track.status == ExplainStatus.COMPLETE } } }
        val saved = AlbumExplainHistory(app).all().first { it.id == state!!.record!!.id }
        assertEquals(listOf(3, 4), saved.tracks.map { it.number })
    }

    @Test fun stopKeepsVisibleTextIgnoresLateCallbackAndCanResume() = runBlocking {
        var first = true
        prepare { _, _, _, _, content ->
            if (first) {
                first = false; content("已显示的歌词解读")
                try { awaitCancellation() } finally { withContext(NonCancellable) { content("迟到的内容") } }
            } else AiChatStreamResult("", "恢复后的完整解读")
        }
        onMain { state!!.generate() }
        await { it.record!!.tracks[0].content == "已显示的歌词解读" }
        onMain { state!!.stop() }
        delay(150)
        onMain {
            assertFalse(state!!.working)
            assertEquals(ExplainStatus.STOPPED, state!!.record!!.tracks[0].status)
            assertEquals("已显示的歌词解读", state!!.record!!.tracks[0].content)
            state!!.generate()
        }
        await { !it.working && it.record!!.tracks.all { track -> track.status == ExplainStatus.COMPLETE } }
    }

    @Test fun favoriteIsPreservedWhenRegeneratingAndCompletedResumeDoesNotFork() = runBlocking {
        var calls = 0
        prepare { _, _, _, _, _ -> calls++; AiChatStreamResult("", "测试解读 $calls") }
        onMain { state!!.generate() }
        await { !it.working && it.record!!.tracks.all { track -> track.status == ExplainStatus.COMPLETE } }
        var original = ""
        onMain { state!!.favorite(); original = state!!.record!!.id; state!!.generate(); assertEquals(original, state!!.record!!.id) }
        await { it.records.any { saved -> saved.id == original && saved.favorite } }
        onMain { state!!.generate(state!!.record!!.tracks[0].id) }
        await { !it.working && it.record!!.id != original && it.record!!.tracks[0].content == "测试解读 3" }
        onMain {
            assertFalse(state!!.record!!.favorite)
            assertEquals("测试解读 1", state!!.records.first { it.id == original }.tracks[0].content)
        }
    }

    @Test fun editedInstrumentalSuppressesLyricsAndTxtExportsInTrackOrder() = runBlocking {
        val prompts = mutableListOf<String>()
        prepare { _, _, user, _, _ -> prompts += user; AiChatStreamResult("", "编曲与律动：测试配器") }
        onMain {
            val track = state!!.record!!.tracks[1]
            state!!.editTrack(track.id, "不应发送的歌词", "用户提供的节奏笔记", true)
            state!!.generate()
        }
        await { !it.working && it.record!!.tracks.all { track -> track.status == ExplainStatus.COMPLETE } }
        assertTrue(prompts[1].contains("用户标记为纯器乐"))
        assertTrue(prompts[1].contains("用户提供的节奏笔记"))
        assertFalse(prompts[1].contains("不应发送的歌词"))
        val output = File(app.cacheDir, "album-fixture-${UUID.randomUUID()}.txt")
        try {
            onMain { state!!.exportText(Uri.fromFile(output)) }
            await { it.notice == "已导出整张专辑的逐曲解读" }
            val text = output.readText()
            assertTrue(text.indexOf("歌曲：3. Holy, Holy") < text.indexOf("歌曲：4. The New Sound"))
            assertTrue(text.contains("编曲与律动：测试配器"))
        } finally { output.delete() }
    }

    @Test fun ttmlAndHistoryDeletionHandleRealStorage() = runBlocking {
        assertEquals("Hello world\n第二行", plainAlbumLyrics("<tt><body><div><p><span>Hello</span><br/><span>world</span></p><p>第二行</p></div></body></tt>", "ttml"))
        val history = AlbumExplainHistory(app)
        val record = AlbumExplainRecord(title = "删除测试", artist = "测试", tracks = listOf(AlbumExplainTrack(number = 1, title = "测试曲", content = "内容")))
        history.put(record)
        history.delete(record.id)
        history.put(record.copy(updatedAt = record.updatedAt + 100))
        assertFalse(history.all().any { it.id == record.id })
    }
}
