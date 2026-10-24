package io.github.proify.lyricon.xposed.systemui.ai.translate

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import io.github.proify.lyricon.lyric.ai.core.AiConfig
import io.github.proify.lyricon.lyric.ai.translate.*
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.LyricWord
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class TranslationLibraryStoreTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var name: String
    private lateinit var cache: AiTranslationCache
    private lateinit var store: TranslationLibraryStore
    private val generation = AtomicInteger()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Before fun setup() {
        name = "library-test-${UUID.randomUUID()}.db"
        cache = AiTranslationCache(20, generation, scope, name)
        store = TranslationLibraryStore(cache)
    }
    @After fun cleanup() = runBlocking { cache.close(); context.deleteDatabase(name); scope.cancel() }
    private suspend fun save(id: String, title: String = "夜航", language: String = "中文", text: String = "海替灯塔等到天明") {
        cache.saveToDb(id, listOf(TranslationItem(2, text)), Song(name = title, artist = "林屿"),
            listOf("intro", "instrumental", "the sea waits"), AiConfig(model = "fixture"), AiTranslationOptions(language))
    }

    @Test fun skippedAndFailedRecordsStayGroupedAndDeletingErrorsKeepsSuccessfulTranslations() = runBlocking {
        cache.init(context); save("success")
        val song = Song(name = "夜航", artist = "林屿", lyrics = listOf(RichLyricLine(text = "the sea waits")))
        cache.saveOutcome(song, "skipped", TranslationDiagnostic("CHINESE", "按设置跳过中文", "关闭跳过中文"))
        cache.saveOutcome(song, "error", TranslationErrors.http(401, "bad key secret-key", "secret-key"),
            AiConfig(apiKey = "secret-key", model = "fixture"), resolution = "Google 保底翻译成功")
        val all = store.list(TranslationLibraryQuery()).songs.single()
        assertEquals(3, all.versions); assertEquals(1, all.errors); assertEquals(1, all.skipped); assertEquals(1, all.translated)
        val failed = store.list(TranslationLibraryQuery(status = "error")).songs.single()
        assertEquals(1, failed.versions)
        val detail = store.versions(failed.key, 0, "error").versions.single()
        assertTrue(detail.reason.contains("HTTP 401")); assertFalse(detail.reason.contains("secret-key"))
        assertTrue(detail.suggestion.contains("API Key")); assertTrue(detail.resolution.contains("Google"))
        assertTrue(store.lines(detail.id, 0).lines.isEmpty())
        assertNull(cache.getFromDb(detail.id))
        assertEquals(0, store.favorite(detail.id, true))
        assertEquals(1, store.delete(emptyList(), listOf(failed.key), "error"))
        assertTrue(store.list(TranslationLibraryQuery(status = "error")).songs.isEmpty())
        assertEquals(2, store.list(TranslationLibraryQuery()).songs.single().versions)
        assertNotNull(cache.getFromDb("success"))
    }
    @Test fun repeatedSkipIsDeduplicatedAndGoogleTranslationsAreMarkedWithTheirEngine() = runBlocking {
        cache.init(context)
        val song = Song(name = "验证歌曲", lyrics = listOf(RichLyricLine(text = "hello")))
        repeat(3) { cache.saveOutcome(song, "skipped", TranslationDiagnostic("DISABLED", "未开启翻译", "开启开关")) }
        val group = store.list(TranslationLibraryQuery()).songs.single()
        val skip = store.versions(group.key, 0).versions.single()
        assertEquals(1, skip.eventCount)
        cache.saveToDb("google", listOf(TranslationItem(0, "你好")), song, listOf("hello"),
            AiConfig(model = "Google Translate"), AiTranslationOptions("中文"), engine = "google")
        assertEquals("google", store.versions(group.key, 0, "translated").versions.single().source)
    }

    @Test fun laterLyricsResolveTheMatchingTracksWaitingRecordWithoutHidingTranslationErrors() = runBlocking {
        cache.init(context)
        val metadata = Song(name = "等待歌曲", artist = "歌手")
        val missing = TranslationDiagnostic("NO_LYRICS", "没有收到可翻译的歌词", "等待来源发送")
        cache.saveOutcome(metadata, "skipped", missing)
        cache.saveOutcome(metadata.copy(artist = "其他歌手"), "skipped", missing)
        cache.saveOutcome(metadata, "error", TranslationErrors.from(java.net.SocketTimeoutException()))
        val group = store.list(TranslationLibraryQuery()).songs.first { it.artist == "歌手" }
        val before = store.versions(group.key, 0, "skipped").versions.single()
        cache.resolveWaitingForLyrics(metadata.copy(lyrics = listOf(RichLyricLine(text = "hello", roma = "hero", translation = "你好"))))
        val after = store.versions(group.key, 0, "skipped").versions.single()
        assertEquals(before.id, after.id)
        assertEquals(before.createdAt, after.createdAt)
        assertTrue(after.reason.contains("曾尚未到达"))
        assertTrue(after.resolution.contains("已收到 1 行歌词"))
        assertTrue(after.resolution.contains("1 行罗马音、1 行已有翻译"))
        assertEquals(1, store.versions(group.key, 0, "error").versions.size)
        val other = store.list(TranslationLibraryQuery()).songs.first { it.artist == "其他歌手" }
        assertEquals("", store.versions(other.key, 0, "skipped").versions.single().resolution)
        cache.resolveWaitingForLyrics(metadata)
        assertEquals(after.resolution, store.versions(group.key, 0, "skipped").versions.single().resolution)
        store.delete(listOf(after.id), emptyList())
        cache.resolveWaitingForLyrics(metadata.copy(lyrics = listOf(RichLyricLine(text = "hello"))))
        assertTrue(store.versions(group.key, 0, "skipped").versions.isEmpty())
    }

    @Test fun successfulTranslationFilterExcludesDiagnosticOnlySongsAndVersionsAndDeletionPreservesDiagnostics() = runBlocking {
        cache.init(context); save("success")
        val metadata = Song(name = "夜航", artist = "林屿")
        cache.saveOutcome(metadata, "error", TranslationErrors.http(401))
        cache.saveOutcome(metadata.copy(name = "只有等待记录"), "skipped", TranslationDiagnostic("NO_LYRICS", "等待歌词", "稍后检查"))
        val page = store.list(TranslationLibraryQuery(status = "translated"))
        assertEquals(1, page.totalSongs)
        val song = page.songs.single()
        assertEquals("夜航", song.title)
        assertEquals(1, song.versions); assertEquals(0, song.errors); assertEquals(0, song.skipped)
        assertEquals(listOf("success"), store.versions(song.key, 0, "translated").versions.map { it.id })
        assertTrue(store.list(TranslationLibraryQuery(search = "只有等待记录", status = "translated")).songs.isEmpty())
        assertEquals(1, store.delete(emptyList(), listOf(song.key), "translated"))
        assertEquals(1, store.versions(song.key, 0, "error").versions.size)
        assertTrue(store.list(TranslationLibraryQuery(status = "translated")).songs.isEmpty())
        assertEquals(1, store.list(TranslationLibraryQuery(status = "skipped")).totalSongs)
    }

    @Test fun groupsVersionsAndFindsOriginalsTranslationsAndLiteralWildcards() = runBlocking {
        cache.init(context)
        save("a"); save("b", language = "日语", text = "Japanese version"); save("c", "另一首", text = "100% completed")
        assertEquals(2, store.list(TranslationLibraryQuery()).songs.size)
        val group = store.list(TranslationLibraryQuery(search = "the sea waits")).songs.first { it.title == "夜航" }
        assertEquals(2, group.versions)
        assertEquals(2, store.versions(group.key, 0).versions.size)
        assertEquals("另一首", store.list(TranslationLibraryQuery(search = "%")).songs.single().title)
        assertEquals(1, store.list(TranslationLibraryQuery(language = "日语")).songs.size)
        assertTrue(store.list(TranslationLibraryQuery(search = "海替灯塔", language = "日语")).songs.isEmpty())
        assertTrue(store.list(TranslationLibraryQuery(search = "index")).songs.isEmpty())
        store.favorite("a", true)
        assertTrue(store.list(TranslationLibraryQuery(language = "日语", favoritesOnly = true)).songs.isEmpty())
        val line = store.lines("a", 0).lines.single()
        assertEquals("the sea waits", line.original)
        assertEquals(2, line.index)
    }

    @Test fun favoritesSurviveUpdatingAndDeletionCannotBeUndoneByLateWrites() = runBlocking {
        cache.init(context); save("a"); save("b", language = "日语")
        store.favorite("a", true); save("a")
        val song = store.list(TranslationLibraryQuery(favoritesOnly = true)).songs.single()
        assertEquals(1, song.favorites)
        assertTrue(cache.getFromMemory("a") != null)
        val oldGeneration = generation.get()
        assertEquals(2, store.delete(emptyList(), listOf(song.key)))
        assertNull(cache.getFromMemory("a"))
        cache.saveToDb("a", listOf(TranslationItem(0, "late")), expectedGeneration = oldGeneration)
        assertNull(cache.getFromMemory("a"))
        assertEquals(0, store.list(TranslationLibraryQuery()).totalVersions)
    }

    @Test fun oldDatabaseMigratesWithoutLosingTranslationsAndCanEnrichMetadata() = runBlocking {
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("CREATE TABLE ai_cache(song_id TEXT PRIMARY KEY,translation_json TEXT,created_at INTEGER)")
            db.execSQL("INSERT INTO ai_cache VALUES(?,?,?)", arrayOf<Any>("legacy", "[{\"index\":2,\"tran\":\"旧译文\"}]", 42))
            db.version = 1
        }
        cache.init(context)
        val old = store.list(TranslationLibraryQuery()).songs.single()
        assertEquals("", old.title); assertEquals(1, old.lines)
        assertEquals("旧译文", store.lines("legacy", 0).lines.single().translation)
        cache.enrichMetadata("legacy", Song(name = "找回歌名", artist = "歌手"), listOf("a", "b", "original"), AiConfig(model = "m"), AiTranslationOptions("中文"))
        assertEquals("找回歌名", store.list(TranslationLibraryQuery()).songs.single().title)
        assertEquals("original", store.lines("legacy", 0).lines.single().original)
    }

    @Test fun timeRangeUsesLatestSongTimeWithInclusiveStartAndExclusiveEnd() = runBlocking {
        cache.init(context)
        save("old", title = "范围前"); save("start", title = "开始边界")
        save("last", title = "结束当天"); save("end", title = "结束边界")
        save("unknown", title = "未记录时间")
        cache.withDatabase { db ->
            listOf("old" to 99L, "start" to 100L, "last" to 199L, "end" to 200L, "unknown" to 0L).forEach { (id, time) ->
                db.execSQL("UPDATE ai_cache SET created_at = ? WHERE song_id = ?", arrayOf<Any>(time, id))
            }
        }
        val query = TranslationLibraryQuery(status = "translated", updatedFrom = 100, updatedBefore = 200)
        val page = store.list(query)
        assertEquals(setOf("开始边界", "结束当天"), page.songs.map { it.title }.toSet())
        assertEquals(listOf(199L, 100L), page.songs.map { it.updatedAt })
        assertEquals(2, page.totalSongs)
        assertEquals(3, store.list(query.copy(updatedBefore = null)).totalSongs)
        assertEquals(4, store.list(query.copy(updatedFrom = null)).totalSongs)
        assertEquals(5, store.list(query.copy(updatedFrom = null, updatedBefore = null)).totalSongs)
        assertTrue(store.list(query.copy(updatedFrom = 200, updatedBefore = 100)).songs.isEmpty())
    }

    @Test fun timeRangeChecksLatestInSelectedStatusAndCombinesWithOtherFilters() = runBlocking {
        cache.init(context)
        save("older"); save("newer", language = "日语")
        store.favorite("older", true)
        cache.saveOutcome(Song(name = "夜航", artist = "林屿"), "error", TranslationErrors.http(401))
        cache.withDatabase { db ->
            db.execSQL("UPDATE ai_cache SET created_at = 300 WHERE status = 'error'")
            db.execSQL("UPDATE ai_cache SET created_at = 100 WHERE song_id = 'older'")
            db.execSQL("UPDATE ai_cache SET created_at = 200 WHERE song_id = 'newer'")
        }
        val query = TranslationLibraryQuery(status = "translated", updatedFrom = 200, updatedBefore = 300,
            language = "中文", favoritesOnly = true, search = "夜航")
        val song = store.list(query).songs.single()
        assertEquals(200L, song.updatedAt)
        assertEquals(2, song.versions)
        assertTrue(store.list(query.copy(updatedBefore = 200)).songs.isEmpty())
        assertTrue(store.list(query.copy(status = "error", language = "", favoritesOnly = false)).songs.isEmpty())
        assertEquals(1, store.list(query.copy(status = "error", language = "", favoritesOnly = false, updatedBefore = 301)).totalSongs)
        assertTrue(store.list(query.copy(language = "韩语")).songs.isEmpty())
    }

    @Test fun timeRangeIsAppliedBeforePaginationAndTotalCount() = runBlocking {
        cache.init(context)
        repeat(35) { save("s$it", title = "song$it") }
        save("excluded", title = "excluded")
        cache.withDatabase { db ->
            db.execSQL("UPDATE ai_cache SET created_at = 150")
            db.execSQL("UPDATE ai_cache SET created_at = 250 WHERE song_id = 'excluded'")
        }
        val query = TranslationLibraryQuery(status = "translated", updatedFrom = 100, updatedBefore = 200, sort = "title")
        val first = store.list(query)
        val second = store.list(query.copy(offset = 30))
        assertEquals(35, first.totalSongs); assertEquals(35, second.totalSongs)
        assertTrue(first.hasMore); assertFalse(second.hasMore)
        assertEquals(30, first.songs.size); assertEquals(5, second.songs.size)
        assertEquals(35, (first.songs + second.songs).map { it.key }.distinct().size)
    }

    @Test fun groupsAndLongSongsArePagedWithoutSkippingContent() = runBlocking {
        cache.init(context)
        repeat(35) { save("s$it", title = "song$it") }
        val first = store.list(TranslationLibraryQuery(sort = "title"))
        val second = store.list(TranslationLibraryQuery(sort = "title", offset = 30))
        assertTrue(first.hasMore); assertFalse(second.hasMore)
        assertEquals(35, (first.songs + second.songs).map { it.key }.distinct().size)
        cache.saveToDb("long", List(150) { TranslationItem(it, "译文 $it") })
        val one = store.lines("long", 0)
        val two = store.lines("long", one.nextOffset!!)
        val three = store.lines("long", two.nextOffset!!)
        assertNull(three.nextOffset)
        assertEquals((0 until 150).toList(), (one.lines + two.lines + three.lines).map { it.index })
    }

    @Test fun timingSummaryKeepsMixedVersionsAndRespectsStatusFilters() = runBlocking {
        cache.init(context)
        cache.saveToDb("word", listOf(TranslationItem(0, "你好")), timedSong(), listOf("hello world"))
        cache.saveToDb("line", listOf(TranslationItem(0, "你好")), timedSong(false), listOf("hello world"))
        cache.saveOutcome(timedSong(false), "error", TranslationDiagnostic("TEST", "test", "test"))
        val all = store.list(TranslationLibraryQuery(status = "translated")).songs.single()
        assertEquals(2, all.versions)
        assertEquals(1, all.wordTimedVersions); assertEquals(1, all.lineTimedVersions)
        assertEquals("逐字 / 逐行", translationTimingLabel(all))
        val errors = store.list(TranslationLibraryQuery(status = "error")).songs.single()
        assertEquals(0, errors.wordTimedVersions); assertEquals(1, errors.lineTimedVersions)
    }

    @Test fun migrationRechecksOldWordFlagsWithoutChangingTranslationsOrFavorites() = runBlocking {
        cache.init(context)
        val repeated = timedSong().copy(lyrics = listOf(RichLyricLine(begin = 60000, end = 62000, text = "hello world",
            words = listOf(LyricWord(begin = 60000, end = 62000, text = "hello "), LyricWord(begin = 60000, end = 62000, text = "world")))))
        cache.saveToDb("old", listOf(TranslationItem(0, "你好")), repeated, listOf("hello world"))
        cache.saveToDb("real", listOf(TranslationItem(0, "世界")), timedSong(), listOf("hello world"))
        store.favorite("old", true)
        cache.withDatabase { db -> db.execSQL("UPDATE ai_cache SET has_word_timing = 1 WHERE song_id = 'old'"); db.version = 4 }
        cache.close()
        cache = AiTranslationCache(20, generation, scope, name); store = TranslationLibraryStore(cache); cache.init(context)
        val group = store.list(TranslationLibraryQuery()).songs.single()
        assertEquals(1, group.wordTimedVersions); assertEquals(1, group.lineTimedVersions)
        val old = store.versions(group.key, 0).versions.first { it.id == "old" }
        assertFalse(old.hasWordTiming); assertTrue(old.favorite)
        assertEquals("你好", store.lines("old", 0).lines.single().translation)
    }

    @Test fun deleteAllLineRecordsIsGlobalPreservesOtherKindsAndInvalidatesLateWrites() = runBlocking {
        cache.init(context)
        repeat(125) { cache.saveToDb("line$it", listOf(TranslationItem(0, "逐行 $it")), timedSong(false), listOf("hello world")) }
        cache.saveToDb("word", listOf(TranslationItem(0, "逐字")), timedSong(), listOf("hello world"))
        save("plain", title = "没有时间轴")
        cache.saveOutcome(timedSong(false), "error", TranslationErrors.http(401))
        cache.saveOutcome(timedSong(false), "skipped", TranslationDiagnostic("DISABLED", "未开启翻译", "开启翻译"))
        store.favorite("line0", true); store.favorite("line124", true); store.favorite("word", true)
        assertEquals(TranslationLineRecordSummary(125, 2), store.lineRecordSummary())
        val before = generation.get()
        assertEquals(125, store.deleteAllLineRecords())
        assertTrue(generation.get() > before)
        assertNull(cache.getFromMemory("line124")); assertNull(cache.getFromDb("line0"))
        assertNotNull(cache.getFromDb("word")); assertNotNull(cache.getFromDb("plain"))
        assertEquals(4, store.list(TranslationLibraryQuery()).totalVersions)
        val group = store.list(TranslationLibraryQuery(status = "translated")).songs.first { it.title == "夜航" }
        assertTrue(store.versions(group.key, 0, "translated").versions.single().hasWordTiming)
        assertTrue(store.versions(group.key, 0, "translated").versions.single().favorite)
        assertEquals(1, store.versions(group.key, 0, "error").versions.size)
        assertEquals(1, store.versions(group.key, 0, "skipped").versions.size)
        cache.saveToDb("line0", listOf(TranslationItem(0, "late")), expectedGeneration = before)
        assertNull(cache.getFromDb("line0"))
        assertEquals(TranslationLineRecordSummary(0, 0), store.lineRecordSummary())
        assertEquals(0, store.deleteAllLineRecords())
    }

    private fun timedSong(words: Boolean = true) = Song(name = "夜航", artist = "林屿", lyrics = listOf(
        RichLyricLine(begin = 60000, end = 62000, text = "hello world", words = if (words) listOf(
            LyricWord(begin = 60000, end = 61000, text = "hello "), LyricWord(begin = 61000, end = 62000, text = "world")) else null)))

    @Test fun storesRealLineAndWordTimingAndPreservesItOnPlainCacheHits() = runBlocking {
        cache.init(context)
        cache.saveToDb("timed", listOf(TranslationItem(0, "你好世界")), timedSong(), listOf("hello world"))
        val song = store.list(TranslationLibraryQuery()).songs.single()
        val version = store.versions(song.key, 0).versions.single()
        assertTrue(version.hasTiming); assertTrue(version.hasWordTiming)
        val line = store.lines("timed", 0).lines.single()
        assertEquals(60000L, line.begin); assertEquals(62000L, line.end)
        assertEquals(listOf("hello ", "world"), line.words.map { it.text })
        cache.enrichMetadata("timed", timedSong(false), listOf("hello world"), AiConfig(), AiTranslationOptions())
        assertEquals(2, store.lines("timed", 0).lines.single().words.size)
    }

    @Test fun versionTwoMigrationPreservesFavoritesAndBackfillsTimingForNamedRecords() = runBlocking {
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("CREATE TABLE ai_cache(song_id TEXT PRIMARY KEY,translation_json TEXT,created_at INTEGER,title TEXT NOT NULL DEFAULT '',artist TEXT NOT NULL DEFAULT '',target_language TEXT NOT NULL DEFAULT '',model TEXT NOT NULL DEFAULT '',original_json TEXT NOT NULL DEFAULT '',search_text TEXT NOT NULL DEFAULT '',line_count INTEGER NOT NULL DEFAULT 0,favorite INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("INSERT INTO ai_cache(song_id,translation_json,created_at,title,artist,original_json,line_count,favorite) VALUES(?,?,?,?,?,?,?,?)", arrayOf<Any>(
                "v2", "[{\"index\":0,\"tran\":\"你好世界\"}]", 42, "夜航", "林屿", "[\"hello world\"]", 1, 1))
            db.version = 2
        }
        cache.init(context)
        val song = store.list(TranslationLibraryQuery()).songs.single()
        val before = store.versions(song.key, 0).versions.single()
        assertTrue(before.favorite); assertFalse(before.hasTiming); assertEquals(42L, before.createdAt)
        cache.enrichMetadata("v2", timedSong(), listOf("hello world"), AiConfig(model = "fixture"), AiTranslationOptions("中文"))
        val after = store.versions(song.key, 0).versions.single()
        assertTrue(after.favorite); assertTrue(after.hasTiming); assertTrue(after.hasWordTiming)
        assertEquals(42L, after.createdAt)
        assertEquals(2, store.lines("v2", 0).lines.single().words.size)
    }
}
