package io.proify.lyricon.app

import io.github.proify.lyricon.app.ai.explain.*
import io.github.proify.lyricon.lyric.ai.core.AiConfig
import org.junit.Assert.*
import org.junit.Test

class ExplainModelsTest {
    private val song = ExplainSong("夜航", "林屿", "候鸟来信",
        listOf(ExplainLine("同一句", 0, 1000), ExplainLine("同一句", 2000, 3000)))
    private val config = AiConfig("test-key", "model-a", "https://example.test/v1")
    private fun key(song: ExplainSong = this.song, range: ExplainRange = ExplainRange.LINE,
        angle: ExplainAngle = ExplainAngle.PHRASE, index: Int = 0, config: AiConfig = this.config,
        language: String = "zh-CN", prompt: String = "prompt") =
        explainCacheKey(song, range, angle, index, config, language, prompt)
    private fun record(id: String = "original", favorite: Boolean = false, time: Long = 1) =
        ExplainRecord(id, song, ExplainRange.LINE, ExplainAngle.PHRASE, 0,
            listOf(ExplainTurn("root", content = "初次解读", status = ExplainStatus.COMPLETE)), time, time, favorite)

    @Test fun cacheSeparatesEveryInterpretationInput() {
        val original = key()
        listOf(key(index = 1), key(range = ExplainRange.SONG), key(angle = ExplainAngle.IMAGERY),
            key(config = config.copy(model = "model-b")), key(config = config.copy(baseUrl = "https://other.test/v1")),
            key(config = config.copy(temperature = .1f)), key(language = "en"), key(prompt = "new"),
            key(song = song.copy(album = "other"))).forEach { assertNotEquals(original, it) }
        assertEquals(original, key())
        assertFalse(original.contains("test-key"))
    }
    @Test fun delimitersInsideMetadataCannotCollide() {
        assertNotEquals(key(song = song.copy(title = "a|b", artist = "c")),
            key(song = song.copy(title = "a", artist = "b|c")))
    }
    @Test fun followUpPreservesRolesAndContextAndSkipsUnansweredQuestions() {
        val turns = record().turns + listOf(
            ExplainTurn("q1", "为什么？", "结合上下文…", status = ExplainStatus.COMPLETE),
            ExplainTurn("failed", "失败的问题", status = ExplainStatus.ERROR))
        val messages = conversationFor(turns, "通俗一点")
        assertEquals(listOf("初次解读", "为什么？", "结合上下文…", "通俗一点"), messages.map { it.content })
        assertEquals(listOf(true, false, true, false), messages.map { it.assistant })
    }
    @Test fun continuingFavoriteCreatesANewUnsavedVersion() {
        val original = record(favorite = true)
        val continuation = original.forContinuation("new-id")
        assertTrue(original.favorite)
        assertEquals("original", original.id)
        assertEquals("new-id", continuation.id)
        assertFalse(continuation.favorite)
        assertEquals(original.turns, continuation.turns)
        assertSame(continuation, continuation.forContinuation("unused"))
    }
    @Test fun oldFavoritesSurviveOrdinaryHistoryPruning() {
        val retained = retainHistory(listOf(record("saved", true, 0), record("old", time = 1),
            record("new", time = 2), record("newest", time = 3)), limit = 2)
        assertEquals(listOf("newest", "new", "saved"), retained.map { it.id })
    }
    @Test fun anglesMatchTheirScope() {
        assertFalse(ExplainAngle.VERSES in anglesFor(ExplainRange.LINE))
        assertFalse(ExplainAngle.PHRASE in anglesFor(ExplainRange.SONG))
        assertTrue(ExplainAngle.LANGUAGE in anglesFor(ExplainRange.LINE))
    }

    @Test fun restoredReadingMatchesScopeAngleAndRepeatedLineOccurrence() {
        val first = record("first").copy(updatedAt = 100)
        val second = record("second").copy(lineIndex = 1, updatedAt = 50)
        val whole = record("whole").copy(range = ExplainRange.SONG, angle = ExplainAngle.OVERVIEW, updatedAt = 1)
        val imagery = whole.copy(id = "imagery", angle = ExplainAngle.IMAGERY, updatedAt = 2)
        val records = listOf(first, second, whole, imagery,
            imagery.copy(id = "other-artist", song = song.copy(artist = "其他歌手"), updatedAt = 500))
        assertEquals("second", latestExplainReading(records, second, true)?.id)
        assertEquals("imagery", latestExplainReading(records, whole, true)?.id)
        assertEquals("whole", latestExplainReading(records, whole, false)?.id)
        assertNull(latestExplainReading(records, second.copy(song = song.copy(lines = song.lines.reversed())), true))
    }
    @Test fun promptIdentifiesRepeatedLineAndAudioLimitations() {
        val prompt = AiExplainPrompt.buildExplainUserPrompt("zh-CN", song.title, song.artist, song.album,
            song.lyrics, ExplainRange.LINE, ExplainAngle.IMAGERY, 1, "同一句")
        assertTrue(prompt.contains("指定第 2 行：同一句"))
        assertTrue(prompt.contains("没有提供音频"))
        assertTrue(prompt.contains("整首歌词仅作为理解上下文"))
    }
    @Test fun songGroupsContainAllVersionsAndSortByLatestActivity() {
        val older = record("older", time = 1)
        val newer = record("newer", favorite = true, time = 5).copy(
            song = song.copy(title = " 夜航 ", album = ""), range = ExplainRange.SONG,
            angle = ExplainAngle.OVERVIEW)
        val other = record("other", time = 3).copy(song = song.copy(title = "另一首歌"))
        val grouped = groupExplainHistory(listOf(older, other, newer))
        assertEquals(2, grouped.size)
        assertEquals(listOf("newer", "older"), grouped.first().records.map { it.id })
        assertEquals("候鸟来信", grouped.first().song.album)
        assertEquals("other", grouped.last().records.single().id)
        assertEquals(grouped.first().key, groupExplainHistory(listOf(newer), favoritesOnly = true).single().key)
    }
    @Test fun sameTitleByDifferentArtistsAndUnknownSongsStaySeparate() {
        val a = record("a")
        val b = record("b").copy(song = song.copy(artist = "另一位歌手"))
        val unknownA = record("c").copy(song = song.copy(title = ""))
        val unknownB = record("d").copy(song = song.copy(title = "", lines = listOf(ExplainLine("另一首的歌词"))))
        assertEquals(4, groupExplainHistory(listOf(a, b, unknownA, unknownB)).size)
    }
}
