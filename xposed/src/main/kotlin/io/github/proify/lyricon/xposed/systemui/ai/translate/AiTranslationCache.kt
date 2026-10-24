/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.xposed.systemui.ai.translate

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import io.github.proify.android.extensions.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.lyric.ai.core.AiConfig
import java.util.Locale
import io.github.proify.lyricon.lyric.ai.translate.hasTranslationWordTiming
import io.github.proify.lyricon.lyric.ai.translate.TranslationTiming
import io.github.proify.lyricon.lyric.ai.translate.TranslationWord
import io.github.proify.lyricon.lyric.ai.translate.TranslationDiagnostic
import io.github.proify.lyricon.lyric.ai.translate.TranslationErrors

/** 已完成整首歌翻译的双级缓存：内存 LRU + SQLite 持久化。 */
internal class AiTranslationCache(
    private val maxCacheSize: Int,
    private val generation: AtomicInteger,
    private val scope: CoroutineScope,
    private val databaseName: String = "lyricon_translation.db"
) {
    private companion object {
        const val TAG = "LyriconAiTranslator"
    }

    private val dbMutex = Mutex()
    private var dbHelper: DatabaseHelper? = null

    private val memory: MutableMap<String, List<TranslationItem>> =
        Collections.synchronizedMap(
            object : LinkedHashMap<String, List<TranslationItem>>(maxCacheSize, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<TranslationItem>>?): Boolean {
                    return size > maxCacheSize
                }
            }
        )

    fun init(context: Context) {
        if (dbHelper == null) {
            synchronized(this) {
                if (dbHelper == null) {
                    Log.d(TAG, "Initializing database...")
                    dbHelper = DatabaseHelper(context.applicationContext, databaseName)
                }
            }
        }
    }

    fun getFromMemory(key: String): List<TranslationItem>? = memory[key]

    fun putMemory(key: String, items: List<TranslationItem>) {
        memory[key] = items
    }

    suspend fun getFromDb(key: String): List<TranslationItem>? = dbMutex.withLock {
        val db = dbHelper?.readableDatabase ?: return null
        return runCatching {
            db.query(
                DatabaseHelper.TABLE_NAME,
                arrayOf(DatabaseHelper.COLUMN_DATA),
                "${DatabaseHelper.COLUMN_ID} = ? AND status = 'translated'",
                arrayOf(key),
                null, null, null
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    val jsonData =
                        cursor.getString(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_DATA))
                    json.decodeFromString<List<TranslationItem>>(jsonData).also { memory[key] = it }
                } else null
            }
        }.getOrElse {
            Log.e(TAG, "DB Query error: ${it.message}")
            null
        }
    }

    suspend fun saveToDb(key: String, items: List<TranslationItem>,
        song: Song? = null, lines: List<String> = emptyList(),
        configs: AiConfig? = null, options: AiTranslationOptions? = null,
        expectedGeneration: Int = generation.get(), engine: String = "ai") {
        val jsonData = json.encodeToString(items)
        dbMutex.withLock {
            if (generation.get() != expectedGeneration) return@withLock
            memory[key] = items
            val db = dbHelper?.writableDatabase ?: return@withLock
            runCatching {
                val values = metadata(song, lines, configs, options).apply {
                    put(DatabaseHelper.COLUMN_ID, key)
                    put(DatabaseHelper.COLUMN_DATA, jsonData)
                    put(DatabaseHelper.COLUMN_TIMESTAMP, System.currentTimeMillis())
                    put("status", "translated"); put("source", engine)
                    put("line_count", items.size)
                    put("search_text", (lines + items.map { it.translation }).joinToString("\n"))
                }
                // Updating an existing entry must preserve the user's favorite flag.
                if (db.update(DatabaseHelper.TABLE_NAME, values, "song_id = ?", arrayOf(key)) == 0)
                    db.insertOrThrow(DatabaseHelper.TABLE_NAME, null, values)
            }.onFailure {
                Log.e(TAG, "Failed to save translation to DB: ${it.message}")
            }
        }
    }

    suspend fun saveOutcome(song: Song, status: String, diagnostic: TranslationDiagnostic,
        configs: AiConfig? = null, options: AiTranslationOptions = AiTranslationOptions(),
        resolution: String = "", expectedGeneration: Int = generation.get()) = dbMutex.withLock {
        require(status in setOf("skipped", "error"))
        if (generation.get() != expectedGeneration) return@withLock
        val db = dbHelper?.writableDatabase ?: return@withLock
        val lines = song.lyrics.orEmpty().map { it.text.orEmpty() }
        val key = "outcome:$status:${diagnostic.code}:" + AiTranslationKey.calculate(configs ?: AiConfig(), options, song, lines)
        val now = System.currentTimeMillis()
        var count = 1
        db.rawQuery("SELECT created_at,event_count FROM ai_cache WHERE song_id = ?", arrayOf(key)).use {
            if (it.moveToFirst()) count = it.getInt(1) + if (now - it.getLong(0) >= 30000) 1 else 0
        }
        val reason = TranslationErrors.redact(diagnostic.reason, configs?.apiKey, 1800)
        val values = metadata(song, lines, configs, options).apply {
            put("song_id", key); put("translation_json", "[]"); put("created_at", now)
            put("status", status); put("reason_code", diagnostic.code); put("reason", reason)
            put("suggestion", TranslationErrors.redact(diagnostic.suggestion, configs?.apiKey, 1800))
            put("resolution", resolution); put("event_count", count); put("source", "ai")
            put("line_count", 0); put("search_text", (lines + reason + diagnostic.suggestion).joinToString("\n"))
        }
        if (db.update("ai_cache", values, "song_id = ?", arrayOf(key)) == 0) db.insertOrThrow("ai_cache", null, values)
    }

    /** A metadata-only event is historical once the same track's lyrics arrive. */
    suspend fun resolveWaitingForLyrics(song: Song) = dbMutex.withLock {
        if (song.name.isNullOrBlank()) return@withLock
        val lines = song.lyrics.orEmpty().filter { !it.text.isNullOrBlank() || !it.words.isNullOrEmpty() }
        if (lines.isEmpty()) return@withLock
        val db = dbHelper?.writableDatabase ?: return@withLock
        val reason = "切歌或匹配时，歌词曾尚未到达"
        val suggestion = "此为此前的等待记录；当前已收到歌词。翻译结果请查看这首歌的最新记录。"
        val resolution = "已收到 ${lines.size} 行歌词，其中 ${lines.count { !it.roma.isNullOrBlank() }} 行罗马音、${lines.count { !it.translation.isNullOrBlank() || !it.translationWords.isNullOrEmpty() }} 行已有翻译"
        db.update("ai_cache", ContentValues().apply {
            put("reason", reason); put("suggestion", suggestion); put("resolution", resolution)
            put("search_text", (lines.map { it.text.orEmpty() } + reason + suggestion + resolution).joinToString("\n"))
        }, "status = 'skipped' AND reason_code = 'NO_LYRICS' AND lower(trim(title)) = lower(trim(?)) AND lower(trim(artist)) = lower(trim(?))",
            arrayOf(song.name.orEmpty(), song.artist.orEmpty()))
    }

    suspend fun enrichMetadata(key: String, song: Song, lines: List<String>, configs: AiConfig,
        options: AiTranslationOptions) = dbMutex.withLock {
        val db = dbHelper?.writableDatabase ?: return@withLock
        val timing = timeline(song)
        var previousTiming = ""
        var previousWordTiming = 0
        val translations = db.rawQuery("SELECT translation_json, original_json, timing_json, has_word_timing FROM ai_cache WHERE song_id = ?", arrayOf(key)).use {
            if (!it.moveToFirst()) return@withLock
            previousTiming = it.getString(2)
            previousWordTiming = it.getInt(3)
            if (it.getString(1).isNotBlank() && (previousTiming == timing || timing.isBlank())) return@withLock
            json.decodeFromString<List<TranslationItem>>(it.getString(0))
        }
        db.update(DatabaseHelper.TABLE_NAME, metadata(song, lines, configs, options).apply {
            put("search_text", (lines + translations.map { it.translation }).joinToString("\n"))
            if (timing.isBlank() && previousTiming.isNotBlank()) {
                put("timing_json", previousTiming); put("has_word_timing", previousWordTiming)
            } else if (previousTiming.isNotBlank()) {
                val previous = json.decodeFromString<List<TranslationTiming>>(previousTiming).associateBy { it.index }
                val merged = timings(song).map { current ->
                    val old = previous[current.index]
                    if (current.words.isEmpty() && old != null && old.begin == current.begin && old.end == current.end)
                        current.copy(words = old.words) else current
                }
                put("timing_json", json.encodeToString(merged))
                put("has_word_timing", if (hasTranslationWordTiming(merged)) 1 else 0)
            }
        }, "song_id = ?", arrayOf(key))
    }

    private fun timings(song: Song?): List<TranslationTiming> = song?.lyrics.orEmpty().mapIndexedNotNull { index, line ->
        val end = if (line.end > line.begin) line.end else
            if (line.duration > 0 && line.begin <= Long.MAX_VALUE - line.duration) line.begin + line.duration else line.begin
        if (line.begin < 0 || end <= line.begin) return@mapIndexedNotNull null
        TranslationTiming(index, line.begin, end, line.words.orEmpty().mapNotNull { word ->
            val text = word.text ?: return@mapNotNull null
            val wordEnd = if (word.end > word.begin) word.end else
                if (word.duration > 0 && word.begin <= Long.MAX_VALUE - word.duration) word.begin + word.duration else word.begin
            if (word.begin < line.begin || wordEnd <= word.begin || wordEnd > end) null
            else TranslationWord(text, word.begin, wordEnd)
        })
    }

    private fun timeline(song: Song?): String = timings(song).takeIf { it.isNotEmpty() }?.let { json.encodeToString(it) }.orEmpty()

    private fun metadata(song: Song?, lines: List<String>, configs: AiConfig?,
        options: AiTranslationOptions?) = ContentValues().apply {
        put("title", song?.name.orEmpty()); put("artist", song?.artist.orEmpty())
        put("target_language", options?.targetLanguage?.takeIf { it.isNotBlank() }
            ?: Locale.getDefault().displayName)
        put("model", configs?.model.orEmpty())
        put("original_json", if (lines.isEmpty()) "" else json.encodeToString(lines))
        val timing = timings(song)
        put("timing_json", if (timing.isEmpty()) "" else json.encodeToString(timing))
        put("has_word_timing", if (hasTranslationWordTiming(timing)) 1 else 0)
    }

    suspend fun <T> withDatabase(invalidate: Boolean = false, block: (SQLiteDatabase) -> T): T =
        dbMutex.withLock {
            val db = checkNotNull(dbHelper) { "翻译数据库尚未初始化" }.writableDatabase
            if (invalidate) { generation.incrementAndGet(); memory.clear() }
            block(db)
        }

    internal suspend fun close() = dbMutex.withLock { dbHelper?.close(); dbHelper = null }

    fun clear(callback: () -> Unit) {
        scope.launch(Dispatchers.IO) {
            dbMutex.withLock {
                generation.incrementAndGet()
                memory.clear()
                runCatching {
                    dbHelper?.writableDatabase?.delete(DatabaseHelper.TABLE_NAME, null, null)
                    Log.d(TAG, "Database cache cleared.")
                    withContext(Dispatchers.Main) { callback() }
                }.onFailure {
                    Log.e(TAG, "Error clearing database: ${it.message}")
                }
            }
        }
    }

    private class DatabaseHelper(context: Context, name: String) :
        SQLiteOpenHelper(context, name, null, DATABASE_VERSION) {

        companion object {
            const val DATABASE_NAME = "lyricon_translation.db"
            const val DATABASE_VERSION = 5
            const val TABLE_NAME = "ai_cache"
            const val COLUMN_ID = "song_id"
            const val COLUMN_DATA = "translation_json"
            const val COLUMN_TIMESTAMP = "created_at"
        }

        override fun onCreate(db: SQLiteDatabase) {
            Log.i(TAG, "Creating translation cache table.")
            db.execSQL(
                """
                CREATE TABLE $TABLE_NAME (
                    $COLUMN_ID TEXT PRIMARY KEY,
                    $COLUMN_DATA TEXT,
                    $COLUMN_TIMESTAMP INTEGER,
                    title TEXT NOT NULL DEFAULT '',
                    artist TEXT NOT NULL DEFAULT '',
                    target_language TEXT NOT NULL DEFAULT '',
                    model TEXT NOT NULL DEFAULT '',
                    original_json TEXT NOT NULL DEFAULT '',
                    search_text TEXT NOT NULL DEFAULT '',
                    timing_json TEXT NOT NULL DEFAULT '',
                    has_word_timing INTEGER NOT NULL DEFAULT 0,
                    line_count INTEGER NOT NULL DEFAULT 0,
                    favorite INTEGER NOT NULL DEFAULT 0,
                    status TEXT NOT NULL DEFAULT 'translated',
                    source TEXT NOT NULL DEFAULT 'ai',
                    reason_code TEXT NOT NULL DEFAULT '',
                    reason TEXT NOT NULL DEFAULT '',
                    suggestion TEXT NOT NULL DEFAULT '',
                    resolution TEXT NOT NULL DEFAULT '',
                    event_count INTEGER NOT NULL DEFAULT 1
                )
            """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_timestamp ON $TABLE_NAME($COLUMN_TIMESTAMP)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                listOf("title", "artist", "target_language", "model", "original_json", "search_text").forEach {
                    db.execSQL("ALTER TABLE $TABLE_NAME ADD COLUMN $it TEXT NOT NULL DEFAULT ''")
                }
                db.execSQL("ALTER TABLE $TABLE_NAME ADD COLUMN line_count INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE $TABLE_NAME ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0")
                db.query(TABLE_NAME, arrayOf(COLUMN_ID, COLUMN_DATA), null, null, null, null, null).use { cursor ->
                    while (cursor.moveToNext()) {
                        val items = runCatching { json.decodeFromString<List<TranslationItem>>(cursor.getString(1)) }.getOrDefault(emptyList())
                        db.update(TABLE_NAME, ContentValues().apply {
                            put("line_count", items.size)
                            put("search_text", items.joinToString("\n") { it.translation })
                        },
                            "$COLUMN_ID = ?", arrayOf(cursor.getString(0)))
                    }
                }
            }
            if (oldVersion < 4) {
                db.execSQL("ALTER TABLE $TABLE_NAME ADD COLUMN status TEXT NOT NULL DEFAULT 'translated'")
                db.execSQL("ALTER TABLE $TABLE_NAME ADD COLUMN source TEXT NOT NULL DEFAULT 'ai'")
                listOf("reason_code", "reason", "suggestion", "resolution").forEach {
                    db.execSQL("ALTER TABLE $TABLE_NAME ADD COLUMN $it TEXT NOT NULL DEFAULT ''")
                }
                db.execSQL("ALTER TABLE $TABLE_NAME ADD COLUMN event_count INTEGER NOT NULL DEFAULT 1")
            }
            if (oldVersion < 3) {
                db.execSQL("ALTER TABLE $TABLE_NAME ADD COLUMN timing_json TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE $TABLE_NAME ADD COLUMN has_word_timing INTEGER NOT NULL DEFAULT 0")
            }
            if (oldVersion < 5) {
                // Recheck old positive flags once; do not reparse every timeline while scrolling.
                db.query(TABLE_NAME, arrayOf(COLUMN_ID, "timing_json"), "has_word_timing = 1",
                    null, null, null, null).use { cursor ->
                    while (cursor.moveToNext()) {
                        val timing = runCatching {
                            json.decodeFromString<List<TranslationTiming>>(cursor.getString(1))
                        }.getOrDefault(emptyList())
                        if (!hasTranslationWordTiming(timing)) db.update(TABLE_NAME,
                            ContentValues().apply { put("has_word_timing", 0) },
                            "$COLUMN_ID = ?", arrayOf(cursor.getString(0)))
                    }
                }
            }
        }
    }
}
