package io.github.proify.lyricon.app.activity.lyric.immersive

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.widget.Toast
import io.github.proify.lyricon.app.bridge.ImmersiveLyricsContract as Contract
import io.github.proify.lyricon.app.bridge.LyriconBridge
import io.github.proify.lyricon.common.PackageNames
import kotlinx.coroutines.*
import kotlin.coroutines.resume

internal object ImmersiveLyricsCommands {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val pendingOffsets = mutableMapOf<String, Job>()

    suspend fun request(context: Context, action: Int, data: Bundle): Bundle {
        return withTimeoutOrNull(12_000) {
            val route = LyriconBridge.with(context.applicationContext).to(PackageNames.SYSTEM_UI)
                .key(Contract.ROUTE).await()
            @Suppress("DEPRECATION")
            val endpoint = route.getParcelable<Messenger>("endpoint") ?: error("请重启系统界面后重试")
            suspendCancellableCoroutine { continuation ->
                val reply = Messenger(Handler(Looper.getMainLooper()) { message ->
                    if (continuation.isActive) continuation.resume(message.data)
                    true
                })
                try {
                    endpoint.send(Message.obtain(null, action).apply { this.data = data; replyTo = reply })
                } catch (error: Exception) { continuation.cancel(error) }
            }
        } ?: error("系统界面未响应，请重启系统界面后重试")
    }

    fun resumeOffset(context: Context, source: String, key: String) {
        val store = ImmersiveOffsetStore.get(context)
        if (store.pending(key) && pendingOffsets[key]?.isActive != true) {
            syncOffset(context, source, key, store.offset(key))
        }
    }

    fun syncOffset(context: Context, source: String, key: String, offset: Int) {
        if (source.isBlank() || key.isBlank()) return
        pendingOffsets.remove(key)?.cancel()
        val app = context.applicationContext
        val job = scope.launch(start = CoroutineStart.LAZY) {
            delay(10_000)
            try {
                val reply = request(app, Contract.SYNC_OFFSET, Bundle().apply {
                    putString("source_token", source); putInt("offset", offset)
                })
                check(reply.getBoolean("accepted")) { reply.getString("error") ?: "歌词偏移同步失败" }
                ImmersiveOffsetStore.get(app).markSynced(key, offset)
                android.util.Log.i("ImmersiveSettings", "offset synchronized value=$offset")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                Toast.makeText(app, error.message ?: "歌词偏移同步失败", Toast.LENGTH_LONG).show()
            }
        }
        pendingOffsets[key] = job
        job.invokeOnCompletion { if (pendingOffsets[key] === job) pendingOffsets.remove(key) }
        job.start()
    }
}
