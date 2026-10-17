package io.github.proify.lyricon.xposed.systemui.ai.translate

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import io.github.proify.lyricon.common.PackageNames
import io.github.proify.lyricon.lyric.ai.core.AiConfig
import io.github.proify.lyricon.lyric.ai.core.IAiChatCallback
import io.github.proify.lyricon.lyric.ai.core.IAiChatService
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/** Bind only while a translation is running; cancellation reaches the app's HTTP call. */
internal object AiTranslationAppClient {
    private var context: Context? = null

    fun initialize(context: Context) {
        this.context = context.applicationContext
    }

    suspend fun complete(config: AiConfig, systemPrompt: String, userPrompt: String): String? {
        val context = context ?: return null
        val id = UUID.randomUUID().toString()
        val lock = Any()
        var remote: IAiChatService? = null
        var connection: ServiceConnection? = null
        val finished = AtomicBoolean(false)
        try {
            return withTimeout(10 * 60_000L) {
                suspendCancellableCoroutine { continuation ->
                    fun finish(content: String?) {
                        if (finished.compareAndSet(false, true)) continuation.resume(content)
                    }
                    val callback = object : IAiChatCallback.Stub() {
                        override fun onResult(content: String?) = finish(content)
                    }
                    val serviceConnection = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                            synchronized(lock) {
                                if (finished.get() || !continuation.isActive) return
                                remote = IAiChatService.Stub.asInterface(binder)
                                try {
                                    remote!!.complete(id, config, systemPrompt, userPrompt, true, callback)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Unable to dispatch translation to app", e)
                                    finish(null)
                                }
                            }
                        }

                        override fun onServiceDisconnected(name: ComponentName) = finish(null)
                        override fun onBindingDied(name: ComponentName) = finish(null)
                        override fun onNullBinding(name: ComponentName) = finish(null)
                    }
                    connection = serviceConnection
                    continuation.invokeOnCancellation {
                        synchronized(lock) {
                            finished.set(true)
                            runCatching { remote?.cancel(id) }
                        }
                    }
                    val intent = Intent().setComponent(ComponentName(
                        PackageNames.APPLICATION,
                        "io.github.proify.lyricon.app.service.AiTranslationService"
                    ))
                    try {
                        val bound = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
                        if (!bound) {
                            Log.e(TAG, "AI translation service could not be bound")
                            finish(null)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Unable to bind AI translation service", e)
                        finish(null)
                    }
                }
            }
        } finally {
            synchronized(lock) {
                finished.set(true)
                runCatching { remote?.cancel(id) }
            }
            connection?.let { runCatching { context.unbindService(it) } }
        }
    }

    private const val TAG = "LyriconAiTranslator"
}
