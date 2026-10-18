package io.github.proify.lyricon.app.ai.translation

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import io.github.proify.lyricon.lyric.ai.translate.TranslationVersion
import io.github.proify.lyricon.lyric.ai.translate.TranslationMusicLocation
import java.security.MessageDigest
import java.util.Locale

internal enum class MusicFileWriteMode { ASK, REPLACE, COPY }
internal data class MusicFolder(val uri: Uri, val name: String)
internal sealed interface MusicFolderWriteResult {
    data class Saved(val name: String) : MusicFolderWriteResult
    data class Exists(val name: String) : MusicFolderWriteResult
}

/** Uses only the directory explicitly granted through Android's folder picker. */
internal class MusicFolderExport(private val context: Context,
    private val persistedAccess: ((Uri) -> Boolean)? = null) {
    private val preferences = context.getSharedPreferences("translation_music_folders", Context.MODE_PRIVATE)
    private val resolver get() = context.contentResolver

    private fun songKey(version: TranslationVersion): String {
        val title = version.title.trim().lowercase(Locale.ROOT)
        val artist = version.artist.trim().lowercase(Locale.ROOT)
        val identity = if (title.isBlank()) "id:${version.id}" else "$title\u001f$artist"
        return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun saved(version: TranslationVersion): MusicFolder? {
        val key = songKey(version)
        val uri = preferences.getString("$key.uri", null)?.let(Uri::parse) ?: return null
        return MusicFolder(uri, preferences.getString("$key.name", "音乐文件夹").orEmpty())
    }

    private fun documentId(uri: Uri): String = if (DocumentsContract.isDocumentUri(context, uri))
        DocumentsContract.getDocumentId(uri) else DocumentsContract.getTreeDocumentId(uri)

    fun hasPersistentWriteAccess(folder: MusicFolder): Boolean = persistedAccess?.invoke(folder.uri) ?: resolver.persistedUriPermissions.any {
        it.isReadPermission && it.isWritePermission && covers(it.uri, folder.uri)
    }

    /** Reuse a grant to a parent folder while writing into the detected child, never the parent. */
    fun detected(location: TranslationMusicLocation): MusicFolder? {
        if (location.folderUri.isBlank()) return null
        val target = Uri.parse(location.folderUri)
        val grant = resolver.persistedUriPermissions.firstOrNull {
            it.isReadPermission && it.isWritePermission && covers(it.uri, target)
        }
        val uri = grant?.let { DocumentsContract.buildDocumentUriUsingTree(it.uri, documentId(target)) } ?: target
        return MusicFolder(uri, location.folderName)
    }

    /** A selected parent grants access; the lyric still belongs beside the matched audio. */
    fun targetWithin(folder: MusicFolder, location: TranslationMusicLocation?): MusicFolder {
        val target = location?.let(::detected) ?: return folder
        if (!covers(folder.uri, target.uri)) return folder
        return MusicFolder(DocumentsContract.buildDocumentUriUsingTree(folder.uri, documentId(target.uri)), target.name)
    }

    internal fun covers(grant: Uri, target: Uri): Boolean = runCatching {
        if (grant.authority != target.authority) return false
        if (grant.authority != "com.android.externalstorage.documents") return grant == target
        val parent = DocumentsContract.getTreeDocumentId(grant).trimEnd('/')
        val child = documentId(target).trimEnd('/')
        child == parent || child.startsWith("$parent/") || (parent.endsWith(':') && child.startsWith(parent))
    }.getOrDefault(false)

    fun describe(uri: Uri): MusicFolder {
        val document = DocumentsContract.buildDocumentUriUsingTree(uri, documentId(uri))
        val name = resolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }.orEmpty().ifBlank { "音乐文件夹" }
        val documentId = documentId(uri)
        val label = if (uri.authority == "com.android.externalstorage.documents") {
            documentId.substringAfter(':').trim('/').ifBlank { name }
        } else name
        return MusicFolder(uri, label)
    }

    fun remember(version: TranslationVersion, folder: MusicFolder) {
        if (!hasPersistentWriteAccess(folder)) return
        val key = songKey(version)
        preferences.edit().putString("$key.uri", folder.uri.toString()).putString("$key.name", folder.name).apply()
    }

    private data class Child(val uri: Uri, val name: String, val mime: String)
    private fun children(tree: Uri): List<Child> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId(tree))
        return resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)
            ?.use { cursor -> buildList {
                while (cursor.moveToNext()) add(Child(DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0)),
                    cursor.getString(1).orEmpty(), cursor.getString(2).orEmpty()))
            } } ?: error("无法读取这个文件夹，请重新选择音乐文件所在文件夹")
    }

    fun write(folder: MusicFolder, fileName: String, mimeType: String, text: String,
        mode: MusicFileWriteMode = MusicFileWriteMode.ASK): MusicFolderWriteResult {
        require(fileName.isNotBlank() && '/' !in fileName && '\\' !in fileName) { "文件名无效" }
        val entries = children(folder.uri)
        val existing = entries.firstOrNull { it.name.equals(fileName, ignoreCase = true) }
        if (existing != null && mode == MusicFileWriteMode.ASK) return MusicFolderWriteResult.Exists(existing.name)
        check(existing == null || mode != MusicFileWriteMode.REPLACE || existing.mime != DocumentsContract.Document.MIME_TYPE_DIR) {
            "同名项目是文件夹，请选择另存一份"
        }
        val replace = existing != null && mode == MusicFileWriteMode.REPLACE
        val outputName = if (existing != null && mode == MusicFileWriteMode.COPY) {
            val base = fileName.substringBeforeLast('.', fileName)
            val extension = fileName.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
            var index = 1
            var candidate = "$base ($index)$extension"
            val usedNames = entries.map { it.name.lowercase(Locale.ROOT) }.toSet()
            while (candidate.lowercase(Locale.ROOT) in usedNames) { index++; candidate = "$base ($index)$extension" }
            candidate
        } else fileName
        val parent = DocumentsContract.buildDocumentUriUsingTree(folder.uri, documentId(folder.uri))
        val destination = if (replace) existing!!.uri else checkNotNull(
            DocumentsContract.createDocument(resolver, parent, mimeType, outputName)) { "无法在所选文件夹创建歌词文件" }
        try {
            checkNotNull(resolver.openOutputStream(destination, "wt")) { "无法写入歌词文件" }
                .bufferedWriter(Charsets.UTF_8).use { it.write(text) }
        } catch (e: Exception) {
            // Only a newly created export is removed on failure; existing user files are never deleted.
            if (!replace) runCatching { DocumentsContract.deleteDocument(resolver, destination) }
            throw e
        }
        val actualName = runCatching { resolver.query(destination, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null } }.getOrNull() ?: outputName
        return MusicFolderWriteResult.Saved(actualName)
    }
}
