package io.github.proify.lyricon.xposed.systemui.lyric

import android.content.Context
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import io.github.proify.lyricon.app.bridge.ImmersiveLyricsContract as Contract
import io.github.proify.lyricon.common.PackageNames
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.xposed.systemui.util.MediaTrackMeta
import io.github.proify.lyricon.xposed.systemui.util.SystemUIMediaUtils
import io.github.proify.lyricon.xposed.systemui.lyric.control.MusicTagLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object ImmersiveLyricsEndpoint {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var endpoint: Messenger? = null
    private var cachedSong: Song? = null
    private var cachedJson = ""
    private var revision = 0L

    fun messenger(context: Context): Messenger = endpoint ?: Messenger(
        Handler(Looper.getMainLooper()) { message ->
            val allowed = context.packageManager.getPackagesForUid(message.sendingUid)
                ?.contains(PackageNames.APPLICATION) == true
            if (!allowed) return@Handler true
            val request = Bundle(message.data)
            val reply = message.replyTo
            val player = LyricViewController.activePackage
            val song = LyricDataHub.immersiveSong()
            val identity = "$player/${song?.id}/${song?.name}/${song?.artist}"
            if (message.what == Contract.SNAPSHOT && reply != null) {
                scope.launch {
                    if (song !== cachedSong) {
                        cachedSong = song
                        cachedJson = withContext(Dispatchers.Default) {
                            song?.let { Json.encodeToString(it) }.orEmpty()
                        }
                        revision++
                    }
                    val musicController = SystemUIMediaUtils.getMusicController(player)
                    val meta = musicController?.metadata?.let(MediaTrackMeta::extract)
                    val playback = SystemUIMediaUtils.getController(player)?.playbackState
                    val position = playback?.position?.takeIf {
                        playback.state == PlaybackState.STATE_PAUSED && it >= 0
                    } ?: LyricViewController.currentLogicPosition
                    val result = Bundle().apply {
                        putString("module_build", ImmersiveNotificationCover.BUILD_ID)
                        putLong("revision", revision)
                        putString("identity", identity)
                        putString("title", meta?.title ?: song?.name)
                        putString("artist", meta?.artist ?: song?.artist)
                        putString("album", meta?.album)
                        putString("player", musicController?.packageName ?: player)
                        putString("media_id", musicController?.metadata
                            ?.getString(android.media.MediaMetadata.METADATA_KEY_MEDIA_ID))
                        putLong("duration", meta?.durationMs ?: song?.duration ?: 0)
                        putLong("position", position)
                        putBoolean("playing", LyricViewController.isPlaying && song != null)
                        putBoolean("seek_and_play", true)
                        if (request.getLong("revision", -1) != revision) {
                            if (cachedJson.toByteArray().size <= 240_000) putString("song", cachedJson)
                            else putString("error", "歌词数据过大，暂时无法显示")
                        }
                    }
                    withContext(Dispatchers.IO) {
                        val cover = ImmersiveArtwork.load(musicController?.packageName ?: player, meta?.title ?: song?.name.orEmpty(),
                            meta?.artist ?: song?.artist.orEmpty(), request.getString("cover_key").orEmpty())
                        result.putString("cover_key", cover.key)
                        cover.bytes?.let { result.putByteArray("cover", it) }
                    }
                    val latest = LyricDataHub.immersiveSong()
                    if ("${LyricViewController.activePackage}/${latest?.id}/${latest?.name}/${latest?.artist}" != identity) return@launch
                    runCatching { reply.send(Message.obtain(null, Contract.SNAPSHOT).apply { data = result }) }
                }
            } else if (message.what == Contract.PREPARE_MUSIC_TAGS) {
                scope.launch {
                    val response = try {
                        check(request.getString("identity") == identity) { "曲目已变化，请重新点击歌名" }
                        MusicTagLauncher.prepare(context)
                    } catch (error: Exception) {
                        Bundle().apply { putBoolean("accepted", false); putString("error", error.message) }
                    }
                    runCatching { reply?.send(Message.obtain(null, Contract.PREPARE_MUSIC_TAGS).apply { data = response }) }
                }
            } else if (message.what == Contract.OPEN_MUSIC_TAGS) {
                val response = Bundle()
                val failure = runCatching {
                    check(request.getString("identity") == identity) { "曲目已变化，请重新点击歌名" }
                    if (request.containsKey("token")) MusicTagLauncher.launchPrepared(context,
                        request.getString("token").orEmpty(), request.getString("choice_id").orEmpty())
                    else MusicTagLauncher.launch(context)
                }.exceptionOrNull()
                response.putBoolean("accepted", failure == null)
                if (failure != null) response.putString("error", failure.message)
                runCatching { reply?.send(Message.obtain(null, Contract.OPEN_MUSIC_TAGS).apply { data = response }) }
            } else if (message.what == Contract.SEEK_AND_PLAY) {
                val response = Bundle()
                val controls = SystemUIMediaUtils.getController(player)?.transportControls
                val failure = runCatching {
                    check(request.getString("identity") == identity && song != null) { "曲目已变化，请重新选择歌词" }
                    check(controls != null) { "当前音乐应用没有可用的播放控制" }
                    val duration = MediaTrackMeta.resolve(player)?.durationMs?.takeIf { it > 0 }
                        ?: song.duration.takeIf { it > 0 }
                        ?: song.lyrics?.maxOfOrNull { it.end }?.coerceAtLeast(0) ?: 0L
                    val target = request.getLong("position").coerceIn(0, duration)
                    controls.seekTo(target)
                    controls.play()
                    response.putLong("position", target)
                    response.putString("identity", identity)
                }.exceptionOrNull()
                response.putBoolean("accepted", failure == null)
                if (failure != null) response.putString("error", failure.message ?: "跳转播放失败")
                runCatching { reply?.send(Message.obtain(null, Contract.SEEK_AND_PLAY).apply { data = response }) }
            } else if (request.getString("identity") == identity && song != null) {
                val controls = SystemUIMediaUtils.getController(player)?.transportControls
                runCatching {
                    when (message.what) {
                        Contract.TOGGLE -> if (LyricViewController.isPlaying) controls?.pause() else controls?.play()
                        Contract.PREVIOUS -> controls?.skipToPrevious()
                        Contract.NEXT -> controls?.skipToNext()
                        Contract.SEEK -> controls?.seekTo(request.getLong("position").coerceIn(0, song.duration.coerceAtLeast(song.lyrics?.maxOfOrNull { it.end } ?: 0)))
                    }
                }
            }
            true
        }
    ).also { endpoint = it }
}
