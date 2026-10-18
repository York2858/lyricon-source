package io.github.proify.lyricon.xposed.systemui.ai.translate

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadata
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import io.github.proify.lyricon.lyric.ai.translate.TranslationMusicLocation
import io.github.proify.lyricon.xposed.systemui.lyric.LyricViewController
import io.github.proify.lyricon.xposed.systemui.lyric.control.AudioTrackIdentity
import io.github.proify.lyricon.xposed.systemui.lyric.control.AudioTrackMatcher
import io.github.proify.lyricon.xposed.systemui.lyric.control.LocalAudioTrack
import io.github.proify.lyricon.xposed.systemui.util.MediaTrackMeta
import io.github.proify.lyricon.xposed.systemui.util.SystemUIMediaUtils
import java.io.File

/** Runs in SystemUI, which already has local media access for the music tag editor. */
internal object TranslationMusicLocator {
    fun locate(context: Context, title: String, artist: String): TranslationMusicLocation {
        if (title.isBlank()) return TranslationMusicLocation(message = "旧记录缺少歌名，请手动选择音乐文件夹")
        val metadata = SystemUIMediaUtils.getController(LyricViewController.activePackage)?.metadata
        val live = metadata?.let(MediaTrackMeta::extract)
        val isCurrent = live?.title?.trim().equals(title.trim(), true) &&
            (artist.isBlank() || live?.artist?.trim().equals(artist.trim(), true))
        val source = if (isCurrent) (metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_URI)
            ?.takeIf { it.isNotBlank() } ?: metadata?.description?.mediaUri?.toString())?.let(Uri::parse) else null
        if (source?.scheme in setOf("http", "https")) return TranslationMusicLocation(
            message = "当前播放的是在线音源，没有可确认的本地目录，可手动选择")
        val resolver = context.contentResolver
        if (source?.scheme == "content") {
            // A player's exact source takes precedence over a similarly named library file.
            runCatching {
                resolver.query(source, arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.DISPLAY_NAME),
                    null, null, null)?.use { c ->
                    if (c.moveToFirst()) folderForPath(c.getString(0), c.getString(1)) else null
                }
            }.getOrNull()?.let { return it }
        }
        val sourcePath = source?.takeIf { it.scheme == "file" }?.path
            ?: source?.takeIf { it.scheme == null && it.path?.startsWith('/') == true }?.path
        if (sourcePath != null) {
            if (File(sourcePath).isFile && File(sourcePath).canRead())
                return folderForPath(sourcePath, File(sourcePath).name) ?: TranslationMusicLocation()
            return TranslationMusicLocation(message = "播放器提供的本地文件已不可用，请检查文件是否移动")
        }
        val track = AudioTrackIdentity(title, artist, if (isCurrent) live?.album else null,
            if (isCurrent) live?.durationMs else null)
        val rows = mutableListOf<Pair<LocalAudioTrack, String>>()
        val columns = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.MIME_TYPE, MediaStore.MediaColumns.DATA)
        try {
            resolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, columns,
                "${MediaStore.Audio.Media.TITLE} = ? COLLATE NOCASE", arrayOf(title.trim()), null)?.use { c ->
                while (c.moveToNext()) rows += LocalAudioTrack(c.getLong(0), c.getString(1).orEmpty(),
                    c.getString(2).orEmpty(), c.getString(3), c.getLong(4), c.getString(5) ?: "audio/*") to c.getString(6).orEmpty()
            }
            val matches = AudioTrackMatcher.matches(track, rows.map { it.first })
            if (matches.size > 1) return TranslationMusicLocation(message = "找到多个同名音乐文件，请手动选择要保存的文件夹")
            val match = matches.singleOrNull() ?: return TranslationMusicLocation()
            val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, match.id)
            resolver.openFileDescriptor(uri, "r")?.use {
                val path = rows.single { it.first.id == match.id }.second
                return folderForPath(path, File(path).name) ?: TranslationMusicLocation(
                    message = "已找到音乐，但该存储位置需手动选择目录")
            }
        } catch (_: SecurityException) {
            return TranslationMusicLocation(message = "系统暂未允许读取本地音乐目录，可手动选择文件夹")
        } catch (_: Exception) {
            return TranslationMusicLocation(message = "暂时无法读取本地音乐目录，可重试或手动选择")
        }
        return TranslationMusicLocation()
    }

    internal fun folderForPath(path: String?, displayName: String?): TranslationMusicLocation? {
        if (path.isNullOrBlank() || path.split('/').any { it == ".." || it == "." }) return null
        val primaryRoots = listOf("/storage/emulated/0/", "/sdcard/", "/storage/self/primary/")
        val primary = primaryRoots.firstOrNull { path.startsWith(it) }
        val volume: String
        val relative: String
        if (primary != null) { volume = "primary"; relative = path.removePrefix(primary) }
        else {
            val match = Regex("^/storage/([0-9a-fA-F]{4}-[0-9a-fA-F]{4})/(.+)$").matchEntire(path) ?: return null
            volume = match.groupValues[1]; relative = match.groupValues[2]
        }
        val parent = relative.substringBeforeLast('/', "").trim('/')
        if (parent.isBlank() || parent.startsWith("Android/", true)) return null
        val name = displayName?.takeIf { it.isNotBlank() } ?: relative.substringAfterLast('/')
        if (name.isBlank() || '/' in name || '\\' in name) return null
        return TranslationMusicLocation(DocumentsContract.buildTreeDocumentUri(
            "com.android.externalstorage.documents", "$volume:$parent").toString(),
            if (volume == "primary") parent else "$volume/$parent", name, "已识别音乐所在文件夹")
    }
}
