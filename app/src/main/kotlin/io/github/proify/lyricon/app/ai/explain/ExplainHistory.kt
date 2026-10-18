package io.github.proify.lyricon.app.ai.explain

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** Serializes mutations and atomically replaces the journal. Saved favorites never expire. */
class ExplainHistory(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "ai_explain_history.json"))
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun all(): List<ExplainRecord> = withContext(Dispatchers.IO) { lock.withLock { read() } }

    suspend fun put(record: ExplainRecord): List<ExplainRecord> = withContext(Dispatchers.IO) {
        lock.withLock {
            val existing = read()
            if (record.id in deletedIds) return@withLock existing
            if (existing.any { it.id == record.id && it.updatedAt > record.updatedAt }) return@withLock existing
            val records = retainHistory(existing.filterNot { it.id == record.id } + record)
            write(records)
            records
        }
    }

    suspend fun delete(ids: Set<String>): List<ExplainRecord> = withContext(Dispatchers.IO) {
        lock.withLock {
            val remaining = read().filterNot { it.id in ids }
            write(remaining)
            // In-flight writes in this process must not resurrect a deleted conversation.
            // No jobs survive a process restart, so these tombstones need not alter the on-disk format.
            deletedIds.addAll(ids)
            remaining
        }
    }

    private fun write(records: List<ExplainRecord>) {
        val output = file.startWrite()
        try {
            output.write(json.encodeToString(records).toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (failure: Throwable) {
            file.failWrite(output)
            throw failure
        }
    }

    private fun read(): List<ExplainRecord> = try {
        file.openRead().use { json.decodeFromString<List<ExplainRecord>>(it.readBytes().toString(Charsets.UTF_8)) }
    } catch (_: java.io.FileNotFoundException) { emptyList() }

    companion object {
        private val lock = Mutex()
        private val deletedIds = mutableSetOf<String>()
    }
}

fun retainHistory(records: List<ExplainRecord>, limit: Int = 120): List<ExplainRecord> {
    val sorted = records.sortedByDescending { it.updatedAt }
    val ordinary = sorted.filterNot { it.favorite }.take(limit).map { it.id }.toSet()
    return sorted.filter { it.favorite || it.id in ordinary }
}
