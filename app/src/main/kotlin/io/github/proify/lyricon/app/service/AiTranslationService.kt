package io.github.proify.lyricon.app.service

import android.app.Service
import android.content.Intent
import android.os.*
import io.github.proify.lyricon.common.PackageNames
import io.github.proify.lyricon.lyric.ai.core.*
import io.github.proify.lyricon.lyric.ai.translate.*
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/** Both translation engines use the app's network policy; callbacks retain safe failure details. */
class AiTranslationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requests = ConcurrentHashMap<String, Job>()
    private val binder = object : IAiChatService.Stub() {
        override fun complete(requestId: String, config: AiConfig, systemPrompt: String, userPrompt: String,
            jsonObject: Boolean, callback: IAiChatCallback) {
            enforceCaller()
            enqueue(requestId, callback, config.apiKey) { AiChatClient.completeOrThrow(config, systemPrompt, userPrompt, jsonObject) }
        }
        override fun translateGoogle(requestId: String, targetLanguage: String?, baseUrl: String, textsJson: String,
            callback: IAiChatCallback) {
            enforceCaller()
            enqueue(requestId, callback) {
                require(textsJson.length <= 380000)
                val texts = TranslationLibraryContract.json.decodeFromString<List<String>>(textsJson)
                require(texts.size <= 2000)
                TranslationLibraryContract.json.encodeToString(GoogleLyricsTranslator.translate(texts, targetLanguage, baseUrl))
            }
        }
        override fun cancel(requestId: String) { enforceCaller(); requests.remove(requestId)?.cancel() }
    }
    private fun enqueue(id: String, callback: IAiChatCallback, secret: String? = null, request: suspend () -> String) {
        lateinit var task: Job
        task = scope.launch(start = CoroutineStart.LAZY) {
            val death = IBinder.DeathRecipient { task.cancel() }
            try {
                callback.asBinder().linkToDeath(death, 0)
                callback.onResult(request())
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val failure = TranslationErrors.from(e, secret)
                runCatching { callback.onError(failure.code, failure.reason, failure.suggestion, failure.httpStatus) }
            } finally { runCatching { callback.asBinder().unlinkToDeath(death, 0) } }
        }
        requests.put(id, task)?.cancel()
        task.invokeOnCompletion { requests.remove(id, task) }; task.start()
    }
    private fun enforceCaller() {
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid()) return
        if (packageManager.getPackagesForUid(uid)?.contains(PackageNames.SYSTEM_UI) != true)
            throw SecurityException("AI translation service is restricted to SystemUI")
    }
    override fun onBind(intent: Intent): IBinder = binder
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
