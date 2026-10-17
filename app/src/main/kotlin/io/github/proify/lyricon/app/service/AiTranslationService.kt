package io.github.proify.lyricon.app.service

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Process
import android.util.Log
import io.github.proify.lyricon.common.PackageNames
import io.github.proify.lyricon.lyric.ai.core.AiChatClient
import io.github.proify.lyricon.lyric.ai.core.AiConfig
import io.github.proify.lyricon.lyric.ai.core.IAiChatCallback
import io.github.proify.lyricon.lyric.ai.core.IAiChatService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** Runs user-configured HTTP requests under the app's network policy, outside SystemUI. */
class AiTranslationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requests = ConcurrentHashMap<String, Job>()

    private val binder = object : IAiChatService.Stub() {
        override fun complete(
            requestId: String,
            config: AiConfig,
            systemPrompt: String,
            userPrompt: String,
            jsonObject: Boolean,
            callback: IAiChatCallback,
        ) {
            enforceCaller()
            lateinit var task: Job
            task = scope.launch(start = CoroutineStart.LAZY) {
                val death = IBinder.DeathRecipient { task.cancel() }
                try {
                    callback.asBinder().linkToDeath(death, 0)
                    Log.i(TAG, "Processing translation request in app process")
                    callback.onResult(AiChatClient.complete(config, systemPrompt, userPrompt, jsonObject))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Translation request failed", e)
                    runCatching { callback.onResult(null) }
                } finally {
                    runCatching { callback.asBinder().unlinkToDeath(death, 0) }
                }
            }
            requests.put(requestId, task)?.cancel()
            task.invokeOnCompletion { requests.remove(requestId, task) }
            task.start()
        }

        override fun cancel(requestId: String) {
            enforceCaller()
            requests.remove(requestId)?.cancel()
        }
    }

    private fun enforceCaller() {
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid()) return
        if (packageManager.getPackagesForUid(uid)?.contains(PackageNames.SYSTEM_UI) != true) {
            throw SecurityException("AI translation service is restricted to SystemUI")
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "LyriconAiService"
    }
}
