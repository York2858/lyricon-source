package io.github.proify.lyricon.xposed.systemui.ai.translate

import android.content.ContentValues
import android.database.Cursor
import io.github.proify.lyricon.lyric.ai.translate.*
import io.github.proify.android.extensions.json

/** All access goes through the cache mutex, including metadata and memory invalidation. */
internal class TranslationLibraryStore(private val cache: AiTranslationCache) {
    private val groupKey = "CASE WHEN trim(title) = '' THEN song_id ELSE lower(trim(title)) || char(31) || lower(trim(artist)) END"
    private val lineRecords = "status = 'translated' AND has_word_timing = 0 AND timing_json != '' AND timing_json != '[]'"

    suspend fun songIdentity(id: String): Pair<String, String> = cache.withDatabase { db ->
        db.rawQuery("SELECT title,artist FROM ai_cache WHERE song_id = ?", arrayOf(id)).use {
            check(it.moveToFirst()) { "这条翻译已被删除，请刷新列表" }
            it.getString(0).orEmpty() to it.getString(1).orEmpty()
        }
    }

    suspend fun list(query: TranslationLibraryQuery): TranslationLibraryPage = cache.withDatabase { db ->
        val conditions = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (query.search.isNotBlank()) {
            conditions += "(title LIKE ? ESCAPE '!' OR artist LIKE ? ESCAPE '!' OR search_text LIKE ? ESCAPE '!')"
            val pattern = "%${query.search.trim().replace("!", "!!").replace("%", "!%").replace("_", "!_")}%"
            repeat(3) { args += pattern }
        }
        if (query.language.isNotBlank()) {
            conditions += "target_language = ?"
            args += query.language
        }
        if (query.favoritesOnly) conditions += "favorite = 1"
        val groupConditions = mutableListOf<String>()
        if (conditions.isNotEmpty()) groupConditions += "MAX(CASE WHEN ${conditions.joinToString(" AND ")} THEN 1 ELSE 0 END) = 1"
        query.updatedFrom?.let { groupConditions += "MAX(created_at) >= CAST(? AS INTEGER)"; args += it.toString() }
        query.updatedBefore?.let { groupConditions += "MAX(created_at) < CAST(? AS INTEGER)"; args += it.toString() }
        val having = if (groupConditions.isEmpty()) "" else " HAVING ${groupConditions.joinToString(" AND ")}"
        val sort = when (query.sort) {
            "oldest" -> "updated ASC, grouping ASC"
            "title" -> "title COLLATE NOCASE ASC, grouping ASC"
            else -> "updated DESC, grouping ASC"
        }
        val statusWhere = when (query.status) { "skipped", "error", "translated" -> " WHERE status = '${query.status}'"; else -> "" }
        val groups = "SELECT $groupKey AS grouping, MAX(title) AS title, MAX(artist) AS artist, COUNT(*) AS versions, SUM(line_count) AS lines, SUM(favorite) AS favorites, MAX(created_at) AS updated, group_concat(DISTINCT target_language) AS languages, SUM(status = 'skipped') AS skipped, SUM(status = 'error') AS errors, SUM(status = 'translated') AS translated, SUM(CASE WHEN has_word_timing = 1 THEN 1 ELSE 0 END) AS word_versions, SUM(CASE WHEN has_word_timing = 0 AND timing_json != '' AND timing_json != '[]' THEN 1 ELSE 0 END) AS line_versions FROM ai_cache$statusWhere GROUP BY grouping$having"
        val songs = db.rawQuery("SELECT * FROM ($groups) ORDER BY $sort LIMIT ${TranslationLibraryContract.PAGE_SIZE + 1} OFFSET ${query.offset.coerceAtLeast(0)}", args.toTypedArray()).use { c ->
            buildList { while (c.moveToNext()) add(TranslationSongEntry(c.str("grouping"), c.str("title"), c.str("artist"), c.num("versions"), c.num("lines"), c.num("favorites"), c.long("updated"), c.str("languages"), c.num("skipped"), c.num("errors"), c.num("translated"), c.num("word_versions"), c.num("line_versions"))) }
        }
        val totalSongs = db.rawQuery("SELECT COUNT(*) FROM ($groups)", args.toTypedArray()).use { it.moveToFirst(); it.getInt(0) }
        val stats = db.rawQuery("SELECT COUNT(*), COALESCE(SUM(line_count),0), COALESCE(SUM(favorite),0) FROM ai_cache", null).use { it.moveToFirst(); listOf(it.getInt(0), it.getInt(1), it.getInt(2)) }
        val languages = db.rawQuery("SELECT DISTINCT target_language FROM ai_cache WHERE target_language != '' ORDER BY target_language", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        val outcomes = db.rawQuery("SELECT SUM(status = 'skipped'),SUM(status = 'error') FROM ai_cache", null).use {
            it.moveToFirst(); it.getInt(0) to it.getInt(1)
        }
        TranslationLibraryPage(songs.take(TranslationLibraryContract.PAGE_SIZE), songs.size > TranslationLibraryContract.PAGE_SIZE,
            totalSongs, stats[0], stats[1], stats[2], languages, outcomes.first, outcomes.second)
    }

    suspend fun versions(group: String, offset: Int, status: String = ""): TranslationVersionPage = cache.withDatabase { db ->
        val statusWhere = when (status) { "skipped", "error", "translated" -> " AND status = '$status'"; else -> "" }
        db.rawQuery("SELECT song_id,title,artist,target_language,model,created_at,line_count,favorite,original_json != '' AS has_original,timing_json != '' AS has_timing,has_word_timing,status,reason,suggestion,resolution,event_count,source FROM ai_cache WHERE ($groupKey) = ?$statusWhere ORDER BY created_at DESC,song_id LIMIT ${TranslationLibraryContract.PAGE_SIZE + 1} OFFSET ${offset.coerceAtLeast(0)}", arrayOf(group)).use { c ->
            val rows = buildList { while (c.moveToNext()) add(TranslationVersion(c.str("song_id"), c.str("title"), c.str("artist"), c.str("target_language"), c.str("model"), c.long("created_at"), c.num("line_count"), c.num("favorite") == 1, c.num("has_original") == 1, c.num("has_timing") == 1, c.num("has_word_timing") == 1, c.str("status"), c.str("reason"), c.str("suggestion"), c.str("resolution"), c.num("event_count"), c.str("source"))) }
            TranslationVersionPage(rows.take(TranslationLibraryContract.PAGE_SIZE), rows.size > TranslationLibraryContract.PAGE_SIZE)
        }
    }

    suspend fun lines(id: String, offset: Int): TranslationLinePage = cache.withDatabase { db ->
        db.rawQuery("SELECT translation_json, original_json,timing_json,status FROM ai_cache WHERE song_id = ?", arrayOf(id)).use { c ->
            check(c.moveToFirst()) { "这条翻译已被删除，请刷新列表" }
            if (c.getString(3) != "translated") return@withDatabase TranslationLinePage(emptyList(), null)
            val translations = json.decodeFromString<List<TranslationItem>>(c.getString(0))
                .filter { it.index >= 0 }.distinctBy { it.index }.sortedBy { it.index }
            val originals = c.getString(1).takeIf { it.isNotBlank() }?.let { json.decodeFromString<List<String>>(it) }.orEmpty()
            val timing = c.getString(2).takeIf { it.isNotBlank() }
                ?.let { json.decodeFromString<List<TranslationTiming>>(it).associateBy { row -> row.index } }.orEmpty()
            val allLines = translationLibraryLines(originals, translations.associate { it.index to it.translation }, timing)
            val start = offset.coerceIn(0, allLines.size)
            var bytes = 0
            val rows = buildList {
                for (row in allLines.drop(start).take(60)) {
                    val size = TranslationLibraryContract.json.encodeToString(row).toByteArray(Charsets.UTF_8).size
                    check(size < 180_000) { "单句内容过长，无法读取" }
                    if (bytes + size > 180_000) break
                    add(row); bytes += size
                }
            }
            TranslationLinePage(rows, (start + rows.size).takeIf { it < allLines.size })
        }
    }

    suspend fun favorite(id: String, favorite: Boolean): Int = cache.withDatabase { db ->
        db.update("ai_cache", ContentValues().apply { put("favorite", if (favorite) 1 else 0) }, "song_id = ? AND status = 'translated'", arrayOf(id))
    }

    suspend fun lineRecordSummary(): TranslationLineRecordSummary = cache.withDatabase { db ->
        db.rawQuery("SELECT COUNT(*), COALESCE(SUM(favorite), 0) FROM ai_cache WHERE $lineRecords", null).use {
            it.moveToFirst(); TranslationLineRecordSummary(it.getInt(0), it.getInt(1))
        }
    }

    suspend fun deleteAllLineRecords(): Int = cache.withDatabase(invalidate = true) { db ->
        db.delete("ai_cache", lineRecords, null)
    }

    suspend fun delete(ids: List<String>, groups: List<String>, status: String = ""): Int = cache.withDatabase(invalidate = true) { db ->
        require(ids.isNotEmpty() || groups.isNotEmpty()) { "请先选择要删除的记录" }
        require(ids.size + groups.size <= 100) { "每次最多删除 100 项" }
        db.beginTransaction()
        try {
            var count = 0
            ids.distinct().forEach { count += db.delete("ai_cache", "song_id = ?", arrayOf(it)) }
            val statusWhere = when (status) { "skipped", "error", "translated" -> " AND status = '$status'"; else -> "" }
            groups.distinct().forEach { count += db.delete("ai_cache", "($groupKey) = ?$statusWhere", arrayOf(it)) }
            db.setTransactionSuccessful()
            count
        } finally { db.endTransaction() }
    }

    private fun Cursor.str(name: String) = getString(getColumnIndexOrThrow(name)).orEmpty()
    private fun Cursor.num(name: String) = getInt(getColumnIndexOrThrow(name))
    private fun Cursor.long(name: String) = getLong(getColumnIndexOrThrow(name))
}
