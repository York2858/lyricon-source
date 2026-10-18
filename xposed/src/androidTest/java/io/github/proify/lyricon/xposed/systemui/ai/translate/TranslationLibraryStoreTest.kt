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
