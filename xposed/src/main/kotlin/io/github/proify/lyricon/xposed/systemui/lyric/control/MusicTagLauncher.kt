package io.github.proify.lyricon.xposed.systemui.lyric.control

import android.content.ClipData
import android.content.ComponentName
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadata
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.Toast
import io.github.proify.lyricon.app.bridge.AppBridge.MusicTagPrefs
import io.github.proify.lyricon.xposed.R
import io.github.proify.lyricon.xposed.logger.YLog
import io.github.proify.lyricon.xposed.systemui.lyric.LyricViewController
import io.github.proify.lyricon.xposed.systemui.lyric.LyricPrefs
import io.github.proify.lyricon.xposed.systemui.util.MediaTrackMeta
import io.github.proify.lyricon.xposed.systemui.util.SystemUIMediaUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Hands the current audio file to the selected editor or Android's Open with chooser. */
internal object MusicTagLauncher {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var activeJob: Job? = null
    internal data class AudioFile(val uri: Uri, val mime: String, val title: String, val detail: String)
    private class AmbiguousAudio : Exception()
    private data class PreparedOpen(val choices: Map<String, Intent>, val expires: Long)
    private val prepared = java.util.concurrent.ConcurrentHashMap<String, PreparedOpen>()

    suspend fun prepare(context: Context): Bundle {
        val metadata = SystemUIMediaUtils.getMusicController(LyricViewController.activePackage)?.metadata
        val meta = metadata?.let(MediaTrackMeta::extract)
        val song = LyricViewController.currentSong
        val track = AudioTrackIdentity(meta?.title ?: song?.name.orEmpty(), meta?.artist ?: song?.artist.orEmpty(),
            meta?.album, meta?.durationMs ?: song?.duration)
        val source = metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_URI)?.takeIf { it.isNotBlank() }
            ?: metadata?.description?.mediaUri?.toString()
        val audio = withContext(Dispatchers.IO) { resolveAudioChoices(context, source, track) }
        check(audio.isNotEmpty()) { ControlUi.moduleResources(context).getString(R.string.control_music_tags_no_audio) }
        prepared.entries.removeIf { it.value.expires < SystemClock.elapsedRealtime() }
        if (prepared.size >= 8) prepared.entries.minByOrNull { it.value.expires }?.let { prepared.remove(it.key) }
        val token = java.util.UUID.randomUUID().toString()
        val choices = audio.map { java.util.UUID.randomUUID().toString() to it }
        prepared[token] = PreparedOpen(choices.associate { (id, file) -> id to
            Intent(Intent.ACTION_VIEW).setDataAndType(file.uri, file.mime).apply {
                clipData = ClipData.newRawUri(file.title, file.uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }, SystemClock.elapsedRealtime() + 120_000)
        return Bundle().apply {
            putBoolean("accepted", true)
            putString("token", token)
            putParcelableArrayList("choices", ArrayList(choices.map { (id, file) -> Bundle().apply {
                putString("choice_id", id)
                putString("title", file.title)
                putString("detail", file.detail)
            } }))
        }
    }

    fun launchPrepared(context: Context, token: String, choiceId: String) {
        val item = prepared[token] ?: error("版本选择已失效，请重新点击歌名")
        check(item.expires >= SystemClock.elapsedRealtime()) { "版本选择已过期，请重新点击歌名" }
        val open = item.choices[choiceId] ?: error("无效的歌曲版本，请重新选择")
        check(readableAudio(context, requireNotNull(open.data)) != null) { "所选音乐文件已无法读取，请重新查找" }
        if (!openDefault(context, open)) {
            @Suppress("DEPRECATION")
            val targets = context.packageManager.queryIntentActivities(open, PackageManager.MATCH_DEFAULT_ONLY)
                .filter { (it.activityInfo.packageName == "com.lonx.lyrico" || it.activityInfo.packageName.startsWith("com.lonx.lyrico.")) &&
                    it.activityInfo.exported && it.activityInfo.enabled && it.activityInfo.applicationInfo.enabled }
                .sortedWith(compareBy({ it.activityInfo.packageName != "com.lonx.lyrico" }, { it.activityInfo.packageName }))
            val target = targets.firstOrNull()?.activityInfo ?: error("未找到支持所选音乐文件的 Lyrico，请先安装")
            context.startActivity(Intent(open).setComponent(ComponentName(target.packageName, target.name)))
        }
        prepared.remove(token)
    }

    fun launch(button: View, onOpened: () -> Unit) {
        launch(button.context, button, onOpened)
    }

    fun launch(context: Context) {
        launch(context, null) {}
    }

    private fun launch(context: Context, button: View?, onOpened: () -> Unit) {
        if (activeJob?.isActive == true) return
        val metadata = SystemUIMediaUtils.getMusicController(LyricViewController.activePackage)?.metadata
        val meta = metadata?.let(MediaTrackMeta::extract)
        val song = LyricViewController.currentSong
        // Later skips must not change the file selected by this click.
        val track = AudioTrackIdentity(meta?.title ?: song?.name.orEmpty(),
            meta?.artist ?: song?.artist.orEmpty(), meta?.album, meta?.durationMs ?: song?.duration)
        val source = metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_URI)
            ?.takeIf { it.isNotBlank() } ?: metadata?.description?.mediaUri?.toString()
        button?.isEnabled = false
        activeJob = scope.launch {
            try {
                val audio = withContext(Dispatchers.IO) { resolveAudio(context, source, track) }
                if (button != null && !button.isAttachedToWindow) return@launch
                if (audio == null) {
                    showMessage(context, R.string.control_music_tags_no_audio)
                    return@launch
                }
                val resources = ControlUi.moduleResources(context)
                val open = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(audio.uri, audio.mime)
                    clipData = ClipData.newRawUri(track.title, audio.uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                if (!openDefault(context, open)) {
                    context.startActivity(Intent.createChooser(open,
                        resources.getString(R.string.control_music_tags_chooser))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    Log.i("LyriconMusicTags", "Opened current audio with system chooser")
                }
                onOpened()
            } catch (e: CancellationException) {
                throw e
            } catch (_: AmbiguousAudio) {
                showMessage(context, R.string.control_music_tags_ambiguous)
            } catch (e: Exception) {
                YLog.error("MusicTagLauncher", "Cannot open current audio", e)
                showMessage(context, R.string.control_music_tags_open_failed)
            } finally {
                button?.isEnabled = true
            }
        }
    }

    private fun openDefault(context: Context, open: Intent): Boolean {
        val prefs = LyricPrefs.baseStylePrefs
        val saved = prefs.getString(MusicTagPrefs.KEY_DEFAULT_COMPONENT, "").orEmpty()
        if (saved.isBlank()) return false
        val component = ComponentName.unflattenFromString(saved)
        // An explicit intent bypasses filter matching; verify support for this file's MIME first.
        @Suppress("DEPRECATION")
        val supported = component != null && context.packageManager.queryIntentActivities(
            Intent(open).setPackage(component.packageName), PackageManager.MATCH_DEFAULT_ONLY
        ).any { info ->
            info.activityInfo.exported && info.activityInfo.enabled &&
                info.activityInfo.applicationInfo.enabled &&
                ComponentName(info.activityInfo.packageName, info.activityInfo.name) == component
        }
        if (supported) {
            try {
                context.startActivity(Intent(open).setComponent(component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                Log.i("LyriconMusicTags", "Opened current audio with default app: ${component.packageName}")
                return true
            } catch (_: ActivityNotFoundException) {
                // App may have been uninstalled between querying and starting it.
            } catch (_: SecurityException) {
                // A disabled or restricted receiver must not crash SystemUI.
            }
        }
        showMessage(context, R.string.control_music_tags_default_unavailable)
        return false
    }

    private fun resolveAudio(context: Context, source: String?, track: AudioTrackIdentity): AudioFile? {
        val matches = resolveAudioChoices(context, source, track)
        if (matches.size > 1) throw AmbiguousAudio()
        return matches.singleOrNull()
    }

    internal fun resolveAudioChoices(context: Context, source: String?, track: AudioTrackIdentity): List<AudioFile> {
        val sourceUri = source?.let(Uri::parse)
        if (sourceUri?.scheme == "content") readableAudio(context, sourceUri)?.let {
            return listOf(it.copy(title = track.title.ifBlank { it.title }, detail = listOfNotNull(
                track.artist.takeIf(String::isNotBlank), track.album?.takeIf(String::isNotBlank), it.detail).joinToString(" · ")))
        }
        // A remote stream must not be replaced with a same-named local download.
        if (sourceUri?.scheme in setOf("http", "https")) return emptyList()
        val path = sourceUri?.takeIf { it.scheme == "file" }?.path
        if (path == null && track.title.isBlank()) return emptyList()
        val columns = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH)
        val selection = if (path != null) "${MediaStore.MediaColumns.DATA} = ?"
            else "${MediaStore.Audio.Media.TITLE} = ? COLLATE NOCASE"
        val tracks = mutableListOf<LocalAudioTrack>()
        val names = mutableMapOf<Long, Pair<String, String>>()
        context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, columns,
            selection, arrayOf(path ?: track.title.trim()), null)?.use { cursor ->
            while (cursor.moveToNext()) {
                names[cursor.getLong(0)] = cursor.getString(6).orEmpty() to cursor.getString(7).orEmpty()
                tracks += LocalAudioTrack(cursor.getLong(0), cursor.getString(1).orEmpty(),
                    cursor.getString(2).orEmpty(), cursor.getString(3), cursor.getLong(4),
                    cursor.getString(5) ?: "audio/*")
            }
        }
        val matches = if (path != null) tracks else AudioTrackMatcher.matches(track, tracks)
        return matches.distinctBy { it.id }.mapNotNull { match ->
            val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, match.id)
            readableAudio(context, uri, match.mime)?.copy(title = match.title,
                detail = listOfNotNull(match.artist.takeIf(String::isNotBlank), match.album?.takeIf(String::isNotBlank),
                    match.durationMs.takeIf { it > 0 }?.let { "%d:%02d".format(it / 60000, it / 1000 % 60) },
                    names[match.id]?.let { (name, folder) -> "$folder$name".takeIf(String::isNotBlank) }
                        ?: "媒体文件 ${match.id}").joinToString(" · "))
        }
    }

    private fun readableAudio(context: Context, uri: Uri, fallbackMime: String = "audio/*"): AudioFile? = try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use {
            val mime = context.contentResolver.getType(uri)?.takeIf { it.startsWith("audio/") } ?: fallbackMime
            AudioFile(uri, mime, uri.lastPathSegment.orEmpty(), uri.toString())
        }
    } catch (_: Exception) { null }

    private fun showMessage(context: Context, message: Int) {
        Toast.makeText(context, ControlUi.moduleResources(context).getString(message), Toast.LENGTH_LONG).show()
    }
}
