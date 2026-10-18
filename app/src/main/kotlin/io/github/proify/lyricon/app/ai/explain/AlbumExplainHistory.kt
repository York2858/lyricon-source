package io.github.proify.lyricon.app.ai.explain

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

internal class AlbumExplainHistory(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "ai_album_explain_history.json"))
    private val json = Json { ignoreUnknownKeys = true }
    suspend fun all(): List<AlbumExplainRecord> = withContext(Dispatchers.IO) { lock.withLock { read() } }
    suspend fun put(record: AlbumExplainRecord): List<AlbumExplainRecord> = withContext(Dispatchers.IO) { lock.withLock {
        val previous = read()
        if (record.id in deleted || previous.any { it.id == record.id && it.updatedAt > record.updatedAt }) return@withLock previous
        val all = (previous.filterNot { it.id == record.id } + record).sortedByDescending { it.updatedAt }
        val ordinary = all.filterNot { it.favorite }.take(30).map { it.id }.toSet()
        val keep = all.filter { it.favorite || it.id in ordinary }
        write(keep); keep
    } }
    suspend fun delete(id: String): List<AlbumExplainRecord> = withContext(Dispatchers.IO) { lock.withLock {
        val keep = read().filterNot { it.id == id }; write(keep); deleted += id; keep
    } }
    private fun read(): List<AlbumExplainRecord> = try {
        file.openRead().use { json.decodeFromString<List<AlbumExplainRecord>>(it.readBytes().toString(Charsets.UTF_8)) }
    } catch (_: java.io.FileNotFoundException) { emptyList() }
    private fun write(records: List<AlbumExplainRecord>) {
        val output = file.startWrite()
        try { output.write(json.encodeToString(records).toByteArray(Charsets.UTF_8)); file.finishWrite(output) }
        catch (failure: Throwable) { file.failWrite(output); throw failure }
    }
    companion object { private val lock = Mutex(); private val deleted = mutableSetOf<String>() }
}
