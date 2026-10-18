package io.github.proify.lyricon.app.ai.translation

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.provider.DocumentsContract

internal object TranslationExportPicker {
    fun folderIntent(context: Context, initial: Uri?, preferSystem: Boolean = true): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            initial?.let { uri -> runCatching {
                if (DocumentsContract.isTreeUri(uri) && !DocumentsContract.isDocumentUri(context, uri))
                    DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri)) else uri
            }.getOrNull()?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) } }
            if (preferSystem) {
                @Suppress("DEPRECATION")
                val handler = context.packageManager.queryIntentActivities(this, 0).firstOrNull {
                    it.activityInfo.packageName in setOf("com.android.documentsui", "com.google.android.documentsui") &&
                        it.activityInfo.exported && it.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0
                }
                handler?.let { setPackage(it.activityInfo.packageName) }
            }
        }

    fun takeFolderGrant(context: Context, resultCode: Int, data: Intent?): Uri {
        require(resultCode == Activity.RESULT_OK) { "已取消文件夹授权" }
        val intent = checkNotNull(data) { "选择器没有返回文件夹，请使用系统授权窗口重试" }
        val uri = intent.data ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            ?: error("选择器没有返回文件夹，请使用系统授权窗口重试")
        require(DocumentsContract.isTreeUri(uri)) { "未获得文件夹权限，请使用系统授权窗口重新选择 Music 或具体子文件夹" }
        val access = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val flags = (intent.flags and access).takeIf { it != 0 } ?: access
        try { context.contentResolver.takePersistableUriPermission(uri, flags) }
        catch (_: SecurityException) { error("系统未授予长期读写权限，请进入 Music 或音乐所在子文件夹后重新授权") }
        check(context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission }) {
            "未获得完整读写权限，请换用系统授权窗口；内部存储根目录和 Download 根目录可能禁止授权"
        }
        return uri
    }
}
