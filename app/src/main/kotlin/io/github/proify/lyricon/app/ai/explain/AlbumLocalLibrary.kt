package io.github.proify.lyricon.app.ai.explain

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

internal class AlbumLocalLibrary(private val context: Context) {
    suspend fun load(album: String, artist: String, current: ExplainSong): AlbumExplainRecord = withContext(Dispatchers.IO) {
        require(album.isNotBlank()) { "请先填写专辑名称" }
        val rows = mutableListOf<LocalAlbumFile>()
        val columns = arrayOf(MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.TRACK, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.VOLUME_NAME)
        context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, columns,
            "${MediaStore.Audio.Media.ALBUM} = ? COLLATE NOCASE", arrayOf(album.trim()), null)?.use { c ->
            while (c.moveToNext()) rows += LocalAlbumFile(c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty(),
                c.getInt(3), c.getString(4).orEmpty(), c.getString(5).orEmpty(), c.getString(6).orEmpty())
        }
        // Multiple directories/formats can be indexed as separate album IDs with the same complete list.
        val groups = groupLocalAlbumCopies(rows)
        val selected = groups.singleOrNull() ?: groups.filter { tracks -> tracks.any {
            it.artist.trim().equals(artist.trim(), true) && (current.title.isBlank() || it.title.trim().equals(current.title.trim(), true))
        } }.singleOrNull() ?: error(if (groups.isEmpty()) "媒体库里没有这张专辑，可手动填写曲目或用 AI 补全后核对" else "存在多个同名专辑版本，请补充艺人信息或手动填写需要的曲序")
        val ordered = groupLocalAlbumFiles(selected)
        require(ordered.size <= 60) { "本地专辑超过 60 首，请分唱片手动导入" }
        val tracks = ordered.mapIndexed { index, copies ->
            val row = copies.first()
            val lyrics = if (current.lyrics.isNotBlank() && row.title.trim().equals(current.title.trim(), true) && current.album.trim().equals(album.trim(), true)) current.lyrics
                else copies.asSequence().map(::readSidecar).firstOrNull { it.isNotBlank() }.orEmpty()
            AlbumExplainTrack(number = row.number ?: index + 1, disc = row.disc,
                title = row.displayTitle, artist = row.artist, lyrics = lyrics.take(20000))
        }
        val duplicateCount = selected.size - tracks.size
        AlbumExplainRecord(title = album.trim(), artist = artist.trim().ifBlank { ordered.firstOrNull()?.firstOrNull()?.artist.orEmpty() },
            tracks = tracks, source = "本地媒体库 · ${tracks.size} 首" +
                (if (duplicateCount > 0) " · 已合并 $duplicateCount 个重复文件" else "") + "（仅本机收录曲目，请核对完整性、版本与曲序）")
    }

    private fun readSidecar(row: LocalAlbumFile): String {
        val relative = row.relativePath.trim('/')
        if (relative.isBlank()) return ""
        val volume = if (row.volume in setOf("external", "external_primary", "")) "primary" else row.volume
        val base = row.fileName.substringBeforeLast('.')
        for (grant in context.contentResolver.persistedUriPermissions.filter { it.isReadPermission && it.uri.authority == "com.android.externalstorage.documents" }) {
            val parent = runCatching { DocumentsContract.getTreeDocumentId(grant.uri) }.getOrNull() ?: continue
            val actualVolume = parent.substringBefore(':')
            if (!actualVolume.equals(volume, true)) continue
            val directory = "$actualVolume:$relative"
            if (directory != parent && !directory.startsWith(parent.trimEnd('/') + "/") && !(parent.endsWith(':') && directory.startsWith(parent))) continue
            for (extension in listOf("lrc", "ttml", "txt")) {
                val uri = DocumentsContract.buildDocumentUriUsingTree(grant.uri, "$directory/$base.$extension")
                val text = runCatching { readBounded(uri) }.getOrNull() ?: continue
                val lyrics = runCatching { plainAlbumLyrics(text, extension) }.getOrDefault("")
                if (lyrics.isNotBlank()) return lyrics
            }
        }
        return ""
    }
    private fun readBounded(uri: Uri): String = checkNotNull(context.contentResolver.openInputStream(uri)).bufferedReader().use { reader ->
        val result = StringBuilder(); val buffer = CharArray(2048)
        while (result.length < 200000) {
            val count = reader.read(buffer, 0, minOf(buffer.size, 200000 - result.length))
            if (count < 0) break
            result.append(buffer, 0, count)
        }
        result.toString()
    }
}

internal fun plainAlbumLyrics(text: String, extension: String): String {
    if (extension == "ttml") {
        val parser = Xml.newPullParser(); parser.setInput(StringReader(text))
        val lines = mutableListOf<String>(); val line = StringBuilder(); var inside = false
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> if (parser.name == "p") { inside = true; line.clear() } else if (inside && parser.name == "br") line.append(' ')
                XmlPullParser.TEXT -> if (inside) line.append(parser.text)
                XmlPullParser.END_TAG -> if (parser.name == "p") { inside = false; line.toString().trim().takeIf { it.isNotEmpty() }?.let(lines::add) }
            }
            parser.next()
        }
        return lines.joinToString("\n").take(20000)
    }
    return text.lineSequence().filterNot { it.trim().matches(Regex("\\[(?:ar|al|ti|by|offset|re|ve):.*]", RegexOption.IGNORE_CASE)) }
        .map { it.replace(Regex("[\\[<]\\d{1,3}:\\d{2}(?:[.:]\\d+)?[\\]>]"), "").trim() }
        .filter { it.isNotBlank() }.joinToString("\n").take(20000)
}
