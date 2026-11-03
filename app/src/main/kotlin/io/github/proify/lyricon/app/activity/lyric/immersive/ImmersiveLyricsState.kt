package io.github.proify.lyricon.app.activity.lyric.immersive

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.proify.lyricon.app.bridge.ImmersiveLyricsContract as Contract
import io.github.proify.lyricon.app.bridge.LyriconBridge
import io.github.proify.lyricon.app.bridge.FavoriteClient
import io.github.proify.lyricon.app.bridge.FavoriteTrack
import io.github.proify.lyricon.common.PackageNames
import io.github.proify.lyricon.app.activity.lyric.AiExplainActivity
import io.github.proify.lyricon.lyric.ai.explain.AiExplainContract
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.LyricWord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlin.coroutines.resume
import java.io.ByteArrayOutputStream

internal class ImmersiveLyricsState(application: Application) : AndroidViewModel(application) {
    var song by mutableStateOf<Song?>(null); private set
    var cover by mutableStateOf<Bitmap?>(null); private set
    var title by mutableStateOf(""); private set
    var artist by mutableStateOf(""); private set
    var album by mutableStateOf(""); private set
    var favorite by mutableStateOf(false); private set
    var favoriteBusy by mutableStateOf(false); private set
    var musicTagChoices by mutableStateOf<List<SongVersionChoice>>(emptyList()); private set
    var musicTagsBusy by mutableStateOf(false); private set
    private var musicTagToken = ""
    private var musicTagIdentity = ""
    private var favoriteTrack: FavoriteTrack? = null
    private var favoriteQuery: kotlinx.coroutines.Job? = null
    private var favoriteCheckedAt = 0L
    val canFavorite: Boolean get() = favoriteTrack != null && !demo
    var playing by mutableStateOf(false); private set
    var demo by mutableStateOf(false); private set
    var status by mutableStateOf("正在连接词幕…"); private set
    var duration by mutableStateOf(0L); private set
    var followRequest by mutableLongStateOf(0L); private set
    var playbackError by mutableStateOf<String?>(null); private set
    private var position by mutableLongStateOf(0L)
    private var sampledAt = 0L
    private var remote: Messenger? = null
    private var revision = -1L
    private var coverKey = ""
    private var identity = ""
    private var seekAndPlaySupported = false
    private var seekGeneration = 0L
    private var pendingSeek: PendingImmersiveSeek? = null
    private val json = Json { ignoreUnknownKeys = true }

    fun currentPosition(): Long = (position + if (playing) {
        (SystemClock.elapsedRealtime() - sampledAt).coerceIn(0, if (demo) 60_000 else if (pendingSeek != null) 3000 else 500)
    } else 0).coerceIn(0, duration.coerceAtLeast(0))

    suspend fun observe() {
        favoriteCheckedAt = 0L
        while (true) {
            if (demo) {
                if (currentPosition() >= duration) seek(0)
                delay(100)
                continue
            }
            if (remote == null) {
                val response = LyriconBridge.with(getApplication()).to(PackageNames.SYSTEM_UI)
                    .key(Contract.ROUTE).await()
                @Suppress("DEPRECATION")
                remote = response.getParcelable("endpoint")
            }
            val response = requestSnapshot()
            if (response == null) {
                remote = null
                identity = ""
                favoriteTrack = null
                favorite = false
                seekGeneration++
                pendingSeek = null
                seekAndPlaySupported = false
                playing = false
                song = null
                cover = null
                revision = -1
                coverKey = ""
                status = "无法连接词幕，请确认模块已激活并重启系统界面"
                delay(1500)
            } else if (!demo) {
                val updatedSong = if (response.containsKey("song")) withContext(Dispatchers.Default) {
                    response.getString("song")?.takeIf { it.isNotBlank() }?.let {
                        runCatching { json.decodeFromString<Song>(it) }.getOrNull()
                    }
                } else if (response.containsKey("error")) null else song
                if (demo) continue
                if (revision == -1L) android.util.Log.i("ImmersiveCover", "client connected module=${response.getString("module_build") ?: "legacy"}")
                val trackChanged = identity != response.getString("identity").orEmpty()
                if (trackChanged) dismissMusicTagChoices()
                song = updatedSong
                revision = response.getLong("revision")
                identity = response.getString("identity").orEmpty()
                seekAndPlaySupported = response.getBoolean("seek_and_play")
                title = response.getString("title").orEmpty()
                artist = response.getString("artist").orEmpty()
                album = response.getString("album").orEmpty()
                duration = maxOf(response.getLong("duration"), updatedSong?.lyrics?.maxOfOrNull { it.end } ?: 0)
                val track = FavoriteTrack(response.getString("player").orEmpty(), response.getString("media_id").orEmpty(),
                    title, artist, album, response.getLong("duration"))
                    .takeIf { it.player.isNotBlank() && it.title.isNotBlank() }
                val favoriteChanged = track != favoriteTrack
                if (favoriteChanged) { favoriteTrack = track; favorite = false }
                if (!favoriteBusy && (favoriteChanged || SystemClock.elapsedRealtime() - favoriteCheckedAt > 5000)) {
                    favoriteCheckedAt = SystemClock.elapsedRealtime()
                    refreshFavorite(track)
                }
                val now = SystemClock.elapsedRealtime()
                val remotePosition = response.getLong("position")
                val remotePlaying = response.getBoolean("playing")
                val seek = pendingSeek
                val holdSeek = seek?.shouldHold(identity, remotePosition, remotePlaying, now) == true
                if (!holdSeek) {
                    if (seek != null && seek.identity == identity && !seek.isConfirmed(remotePosition, remotePlaying, now)) {
                        playbackError = "音乐应用未确认跳转，请在原播放器检查"
                    }
                    pendingSeek = null
                    position = remotePosition
                    sampledAt = now
                    playing = remotePlaying
                }
                val nextCoverKey = response.getString("cover_key").orEmpty()
                if (trackChanged) { cover = null; coverKey = "" }
                if (nextCoverKey != coverKey) {
                    val bytes = response.getByteArray("cover")
                    val decoded = withContext(Dispatchers.Default) {
                        bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                    }
                    if (decoded != null) {
                        cover = decoded
                        coverKey = nextCoverKey
                        android.util.Log.i("ImmersiveCover", "client decoded bytes=${bytes?.size} size=${decoded.width}x${decoded.height}")
                    }
                }
                status = response.getString("error") ?: "暂无完整歌词，请在音乐应用中播放歌曲并启用词幕歌词源"
                delay(250)
            }
        }
    }

    private suspend fun requestSnapshot(): Bundle? = request(Contract.SNAPSHOT, Bundle().apply {
        putLong("revision", revision)
        putString("cover_key", coverKey)
    })

    private suspend fun request(action: Int, payload: Bundle, timeout: Long = 2000): Bundle? = withTimeoutOrNull(timeout) {
        suspendCancellableCoroutine { continuation ->
            val reply = Messenger(Handler(Looper.getMainLooper()) { message ->
                if (continuation.isActive) continuation.resume(message.data)
                true
            })
            runCatching {
                val endpoint = remote ?: error("词幕连接已断开")
                endpoint.send(Message.obtain(null, action).apply {
                    replyTo = reply
                    data = payload
                })
            }.onFailure { if (continuation.isActive) continuation.resume(null) }
        }
    }

    fun command(action: Int) {
        if (demo) {
            when (action) {
                Contract.TOGGLE -> {
                    position = currentPosition()
                    sampledAt = SystemClock.elapsedRealtime()
                    playing = !playing
                }
                Contract.NEXT -> seek((currentPosition() + 6000).coerceAtMost(duration))
                Contract.PREVIOUS -> seek((currentPosition() - 6000).coerceAtLeast(0))
            }
        } else send(action)
    }

    fun seek(target: Long) {
        if (demo) {
            position = target.coerceIn(0, duration)
            sampledAt = SystemClock.elapsedRealtime()
        } else send(Contract.SEEK, target)
    }

    fun seekAndPlay(target: Long) {
        playbackError = null
        if (demo) {
            seek(target)
            playing = true
            followRequest++
            return
        }
        if (!seekAndPlaySupported) {
            playbackError = "请重启系统界面，启用歌词跳转播放"
            return
        }
        val requestedIdentity = identity
        val generation = ++seekGeneration
        val requestedPosition = target.coerceIn(0, duration.coerceAtLeast(0))
        viewModelScope.launch {
            val response = request(Contract.SEEK_AND_PLAY, Bundle().apply {
                putString("identity", requestedIdentity)
                putLong("position", requestedPosition)
            })
            if (generation != seekGeneration || identity != requestedIdentity || demo) return@launch
            if (response?.getBoolean("accepted") != true || response.getString("identity") != requestedIdentity) {
                playbackError = response?.getString("error") ?: "跳转播放未响应，请重试"
                return@launch
            }
            position = response.getLong("position")
            sampledAt = SystemClock.elapsedRealtime()
            playing = true
            pendingSeek = PendingImmersiveSeek(requestedIdentity, position, sampledAt)
            followRequest++
        }
    }

    fun clearPlaybackError() { playbackError = null }

    private fun refreshFavorite(track: FavoriteTrack?) {
        favoriteQuery?.cancel()
        if (track == null) return
        favoriteQuery = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { FavoriteClient.request(getApplication(), track) }.getOrNull()
            }
            if (favoriteTrack == track && !favoriteBusy && result?.getBoolean("accepted") == true) {
                favorite = result.getBoolean("favorite")
            }
        }
    }

    fun toggleFavorite() {
        val track = favoriteTrack ?: return
        if (favoriteBusy || demo) return
        favoriteQuery?.cancel()
        favoriteBusy = true
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    FavoriteClient.toggle(getApplication(), track)
                }
                check(result.getBoolean("accepted")) { result.getString("error") ?: "喜爱记录保存失败" }
                if (favoriteTrack == track) favorite = result.getBoolean("favorite")
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                playbackError = when (error) {
                    is IllegalArgumentException, is SecurityException -> "请安装支持喜爱记录的新版本地歌词服务，并启用当前播放器"
                    else -> error.message ?: "本地歌词服务未响应，喜爱未保存"
                }
            } finally { favoriteBusy = false }
        }
    }

    fun openMusicTags() {
        if (demo) { playbackError = "示例歌曲没有可打开的音乐文件"; return }
        if (musicTagsBusy || musicTagChoices.isNotEmpty()) return
        val requestedIdentity = identity
        musicTagsBusy = true
        viewModelScope.launch {
            try {
                val response = request(Contract.PREPARE_MUSIC_TAGS, Bundle().apply { putString("identity", requestedIdentity) }, 10000)
                if (identity != requestedIdentity) return@launch
                check(response?.getBoolean("accepted") == true) { response?.getString("error") ?: "请重启系统界面后再打开 Lyrico" }
                @Suppress("DEPRECATION")
                val choices = response.getParcelableArrayList<Bundle>("choices").orEmpty().map {
                    SongVersionChoice(it.getString("choice_id").orEmpty(), it.getString("title").orEmpty(), it.getString("detail").orEmpty())
                }.filter { it.id.isNotBlank() }
                check(choices.isNotEmpty()) { "未找到可用的音乐文件" }
                musicTagToken = response.getString("token").orEmpty()
                musicTagIdentity = requestedIdentity
                musicTagChoices = choices
                if (choices.size == 1) {
                    launchMusicTagVersion(choices.single())
                }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                playbackError = error.message ?: "无法打开 Lyrico"
            } finally { musicTagsBusy = false }
        }
    }

    fun dismissMusicTagChoices() { musicTagChoices = emptyList(); musicTagToken = "" }

    fun selectMusicTagVersion(choice: SongVersionChoice) {
        if (musicTagsBusy || choice !in musicTagChoices) return
        val token = musicTagToken
        val targetIdentity = musicTagIdentity
        musicTagsBusy = true
        viewModelScope.launch {
            try {
                launchMusicTagVersion(choice, token, targetIdentity)
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                playbackError = error.message ?: "无法打开所选歌曲版本"
            } finally { musicTagsBusy = false }
        }
    }

    private suspend fun launchMusicTagVersion(choice: SongVersionChoice, token: String = musicTagToken,
        targetIdentity: String = musicTagIdentity) {
        val response = request(Contract.OPEN_MUSIC_TAGS, Bundle().apply {
            putString("identity", targetIdentity); putString("token", token); putString("choice_id", choice.id)
        })
        check(response?.getBoolean("accepted") == true) { response?.getString("error") ?: "打开 Lyrico 未响应，请重试" }
        dismissMusicTagChoices()
    }

    fun explanationIntent(context: Context): Intent = Intent(context, AiExplainActivity::class.java)
        .putExtras(explanationSnapshot())
        .putExtra(AiExplainActivity.EXTRA_FROM_IMMERSIVE, true)

    fun explanationSnapshot(): Bundle = Bundle().apply {
        putString(AiExplainContract.EXTRA_TITLE, title)
        putString(AiExplainContract.EXTRA_ARTIST, artist)
        putString(AiExplainContract.EXTRA_ALBUM, album)
        val lines = song?.lyrics.orEmpty()
        putStringArrayList(AiExplainContract.EXTRA_LINE_TEXTS, ArrayList(lines.map { it.text }))
        putLongArray(AiExplainContract.EXTRA_LINE_STARTS, lines.map { it.begin }.toLongArray())
        putLongArray(AiExplainContract.EXTRA_LINE_ENDS, lines.map { it.end }.toLongArray())
        putLong(AiExplainContract.EXTRA_POSITION, currentPosition())
        cover?.let { bitmap ->
            val scale = minOf(1f, 256f / maxOf(bitmap.width, bitmap.height))
            val thumbnail = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1), true)
            val bytes = ByteArrayOutputStream().use { output ->
                thumbnail.compress(Bitmap.CompressFormat.JPEG, 85, output)
                output.toByteArray()
            }
            if (thumbnail !== bitmap) thumbnail.recycle()
            if (bytes.size <= 100_000) putByteArray(AiExplainContract.EXTRA_COVER, bytes)
        }
    }

    private fun send(action: Int, target: Long = 0) {
        runCatching { remote?.send(Message.obtain(null, action).apply {
            data = Bundle().apply { putString("identity", identity); putLong("position", target) }
        }) }
    }

    fun toggleDemo() {
        seekGeneration++
        pendingSeek = null
        demo = !demo
        revision = -1
        coverKey = ""
        cover = null
        position = 0
        sampledAt = SystemClock.elapsedRealtime()
        if (demo) {
            song = immersiveDemoSong()
            title = "词幕 · 沉浸歌词示例"
            artist = "示例预览 · 不播放音频"
            album = ""
            duration = song!!.duration
            playing = true
        } else {
            song = null
            playing = false
            status = "正在连接词幕…"
        }
    }
}

internal fun immersiveDemoSong(): Song {
    val texts = listOf("让每一个字，慢慢亮起", "让旋律经过你的眼睛", "风吹过夜色，星光落在窗边", "此刻只需听见，心里的声音", "跟着音乐，读懂每一句", "把这段时光，留在歌声里")
    val translations = listOf("Let every word slowly light up", "Let the melody pass before your eyes", "Stars fall by the window as the wind crosses the night", "Listen to the voice within", "Follow the music, feel every line", "Keep this moment in the song")
    return Song(name = "词幕 · 沉浸歌词示例", artist = "示例预览", duration = 38_000,
        lyrics = texts.mapIndexed { index, text ->
            val begin = 1000L + index * 6000
            RichLyricLine(begin = begin, end = begin + 5000, text = text, translation = translations[index],
                roma = if (index == 0) "ràng měi yí gè zì, màn màn liàng qǐ" else null,
                words = text.mapIndexed { offset, character ->
                    LyricWord(begin = begin + offset * 5000 / text.length, end = begin + (offset + 1) * 5000 / text.length, text = character.toString())
                })
        })
}
