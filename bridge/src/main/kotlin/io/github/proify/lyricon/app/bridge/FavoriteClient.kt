package io.github.proify.lyricon.app.bridge

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Messenger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

data class FavoriteTrack(val player: String, val mediaId: String, val title: String,
    val artist: String, val album: String, val duration: Long)

object FavoriteClient {
    private const val PROVIDER = "io.github.proify.lyricon.localprovider"

    suspend fun toggle(context: Context, track: FavoriteTrack): Bundle {
        val current = request(context, track)
        check(current.getBoolean("accepted")) { current.getString("error") ?: "无法读取喜爱记录" }
        return request(context, track, !current.getBoolean("favorite"))
    }

    suspend fun request(context: Context, track: FavoriteTrack, favorite: Boolean? = null): Bundle = withContext(Dispatchers.IO) {
        val data = Bundle().apply {
            putString("player", track.player); putString("media_id", track.mediaId)
            putString("title", track.title); putString("artist", track.artist)
            putString("album", track.album); putLong("duration", track.duration)
            favorite?.let { putBoolean("favorite", it) }
        }
        requestPlayer(context, track, data) ?: error("播放器内的收藏记录服务未连接，请重启播放器并确认已启用本地歌词支持的作用域")
    }

    private suspend fun requestPlayer(context: Context, track: FavoriteTrack, data: Bundle): Bundle? = withTimeoutOrNull(1000) {
        suspendCancellableCoroutine { continuation ->
            val requestId = java.util.UUID.randomUUID().toString()
            val reply = Messenger(Handler(Looper.getMainLooper()) { message ->
                val trusted = context.packageManager.getPackagesForUid(message.sendingUid)?.contains(track.player) == true
                if (trusted && message.data.getString("request_id") == requestId && continuation.isActive) {
                    continuation.resume(message.data)
                }
                true
            })
            val sender = PendingIntent.getBroadcast(context, 0,
                Intent("io.github.proify.lyricon.FAVORITE_SENDER").setPackage(context.packageName),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            runCatching {
                context.sendBroadcast(Intent("$PROVIDER.PLAYER_FAVORITE").setPackage(track.player)
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND).putExtra("track", data)
                    .putExtra("request_id", requestId).putExtra("sender", sender).putExtra("reply", reply))
            }.onFailure { if (continuation.isActive) continuation.resume(null) }
        }
    }

}
