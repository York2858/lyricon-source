package io.github.proify.lyricon.app.ai.translation

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import java.io.File
import java.util.UUID

/** An isolated DocumentsProvider verifies the real SAF APIs without touching the user's storage. */
class TestMusicDocumentsProvider : DocumentsProvider() {
    private val root get() = File(context!!.cacheDir, "music-folder-documents").apply { mkdirs() }.canonicalFile
    private fun file(id: String): File {
        val file = if (id == "root") root else File(root, id).canonicalFile
        check(file == root || file.path.startsWith(root.canonicalPath + File.separator))
        return file
    }
    override fun onCreate() = true
    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: arrayOf(
        DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID,
        DocumentsContract.Root.COLUMN_TITLE, DocumentsContract.Root.COLUMN_FLAGS)).apply {
        val values: Map<String, Any?> = mapOf(DocumentsContract.Root.COLUMN_ROOT_ID to "root", DocumentsContract.Root.COLUMN_DOCUMENT_ID to "root",
            DocumentsContract.Root.COLUMN_TITLE to "歌词导出测试目录", DocumentsContract.Root.COLUMN_FLAGS to DocumentsContract.Root.FLAG_SUPPORTS_CREATE)
        addRow(columnNames.map { values[it] }.toTypedArray())
    }
    private fun document(id: String, projection: Array<out String>?): MatrixCursor {
        val file = file(id)
        return MatrixCursor(projection ?: columns).apply { add(file, id) }
    }
    private fun MatrixCursor.add(file: File, id: String) {
        val values: Map<String, Any?> = mapOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID to id,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME to file.name,
            DocumentsContract.Document.COLUMN_MIME_TYPE to if (file.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else "text/plain",
            DocumentsContract.Document.COLUMN_SIZE to file.length(),
            DocumentsContract.Document.COLUMN_FLAGS to if (file.isDirectory) DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
                else DocumentsContract.Document.FLAG_SUPPORTS_WRITE or DocumentsContract.Document.FLAG_SUPPORTS_DELETE)
        addRow(columnNames.map { values[it] }.toTypedArray())
    }
    override fun queryDocument(documentId: String, projection: Array<out String>?) = document(documentId, projection)
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor =
        MatrixCursor(projection ?: columns).apply {
            file(parentDocumentId).listFiles().orEmpty().forEach { add(it, it.relativeTo(root).path) }
        }
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file(documentId), ParcelFileDescriptor.parseMode(mode))
    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        require('/' !in displayName && '\\' !in displayName)
        val target = File(file(parentDocumentId), displayName)
        check(target.createNewFile())
        return target.relativeTo(root).path
    }
    override fun deleteDocument(documentId: String) { check(file(documentId).delete()) }
    override fun isChildDocument(parentDocumentId: String, documentId: String) =
        file(documentId).path.startsWith(file(parentDocumentId).path + File.separator)

    companion object {
        private val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_FLAGS)
        fun createFolder(context: Context): Pair<File, android.net.Uri> {
            val folder = File(context.cacheDir, "music-folder-documents/${UUID.randomUUID()}").apply { mkdirs() }
            return folder to DocumentsContract.buildTreeDocumentUri("${context.packageName}.test.musicfolders", folder.name)
        }
    }
}
