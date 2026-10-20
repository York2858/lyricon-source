package io.github.proify.lyricon.xposed.systemui.ai.translate

import android.content.*
import android.os.IBinder
import io.github.proify.lyricon.common.PackageNames
import io.github.proify.lyricon.lyric.ai.core.*
import io.github.proify.lyricon.lyric.ai.translate.*
import kotlinx.coroutines.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal object AiTranslationAppClient {
    private var context: Context? = null
    fun initialize(context: Context) { this.context = context.applicationContext }
    suspend fun complete(config: AiConfig, systemPrompt: String, userPrompt: String): String =
        perform { service, id, callback -> service.complete(id, config, systemPrompt, userPrompt, true, callback) }
    suspend fun google(language: String?, baseUrl: String, texts: List<String>): String =
        perform { service, id, callback -> service.translateGoogle(id, language, baseUrl,
            TranslationLibraryContract.json.encodeToString(texts), callback) }

    private suspend fun perform(dispatch: (IAiChatService, String, IAiChatCallback) -> Unit): String {
        val app = context ?: throw failure("SERVICE_INIT", "翻译连接尚未初始化")
        val id = UUID.randomUUID().toString(); val lock = Any(); val finished = AtomicBoolean(false)
        var remote: IAiChatService? = null; var connection: ServiceConnection? = null
        try {
            return withTimeout(120000) {
                suspendCancellableCoroutine { continuation ->
                    fun finish(value: String?, error: TranslationFailure? = null) {
                        if (finished.compareAndSet(false, true) && continuation.isActive) {
                            if (error != null) continuation.resumeWithException(error)
                            else if (value.isNullOrBlank()) continuation.resumeWithException(failure("EMPTY_RESPONSE", "翻译服务未返回内容"))
                            else continuation.resume(value)
                        }
                    }
                    val callback = object : IAiChatCallback.Stub() {
                        override fun onResult(content: String?) = finish(content)
                        override fun onError(code: String?, reason: String?, suggestion: String?, httpStatus: Int) =
                            finish(null, TranslationFailure(TranslationDiagnostic(code.orEmpty(), reason.orEmpty(), suggestion.orEmpty(), httpStatus)))
                    }
                    val serviceConnection = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                            synchronized(lock) {
                                if (finished.get() || !continuation.isActive) return
                                remote = IAiChatService.Stub.asInterface(binder)
                                try { dispatch(remote!!, id, callback) }
                                catch (_: Exception) { finish(null, failure("SERVICE_DISPATCH", "无法向词幕应用提交翻译请求")) }
                            }
                        }
                        override fun onServiceDisconnected(name: ComponentName) = finish(null, failure("SERVICE_DISCONNECTED", "翻译过程中词幕应用连接断开"))
                        override fun onBindingDied(name: ComponentName) = finish(null, failure("SERVICE_DIED", "翻译服务进程已退出"))
                        override fun onNullBinding(name: ComponentName) = finish(null, failure("SERVICE_BIND", "词幕应用拒绝了翻译服务连接"))
                    }
                    connection = serviceConnection
                    continuation.invokeOnCancellation { synchronized(lock) { finished.set(true); runCatching { remote?.cancel(id) } } }
                    try {
                        if (!app.bindService(Intent().setComponent(ComponentName(PackageNames.APPLICATION,
                            "io.github.proify.lyricon.app.service.AiTranslationService")), serviceConnection, Context.BIND_AUTO_CREATE))
                            finish(null, failure("SERVICE_BIND", "无法连接词幕翻译服务"))
                    } catch (_: Exception) { finish(null, failure("SERVICE_BIND", "无法启动词幕翻译服务")) }
                }
            }
        } catch (_: TimeoutCancellationException) { throw failure("TIMEOUT", "等待翻译服务响应超过 120 秒") }
        finally {
            synchronized(lock) { finished.set(true); runCatching { remote?.cancel(id) } }
            connection?.let { runCatching { app.unbindService(it) } }
        }
    }
    private fun failure(code: String, reason: String) = TranslationFailure(TranslationDiagnostic(code, reason,
        "检查词幕应用是否被限制后台运行；更新模块后需要重新加载系统界面。"))
}
