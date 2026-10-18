package io.github.proify.lyricon.xposed.systemui.ai.translate

import android.content.Context
import android.os.*
import io.github.proify.lyricon.common.Constants
import io.github.proify.lyricon.lyric.ai.translate.*
import kotlinx.coroutines.*

/** The broadcast only discovers this endpoint. Messenger authenticates each real request by UID. */
internal class TranslationLibraryEndpoint(private val context: Context, cache: AiTranslationCache) {
    private val packageManager = context.packageManager
    private val store = TranslationLibraryStore(cache)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        val allowed = packageManager.getPackagesForUid(message.sendingUid)
            ?.contains(Constants.APP_PACKAGE_NAME) == true
        if (allowed && message.replyTo != null) {
            val what = message.what
            val request = Bundle(message.data)
            val reply = message.replyTo
            scope.launch {
                val result = Bundle()
                try {
                    val json = TranslationLibraryContract.json
                    val encoded = when (what) {
                        TranslationLibraryContract.LIST -> json.encodeToString(store.list(json.decodeFromString<TranslationLibraryQuery>(request.getString("query") ?: "{}")))
                        TranslationLibraryContract.VERSIONS -> json.encodeToString(store.versions(request.getString("group").orEmpty(), request.getInt("offset")))
                        TranslationLibraryContract.LINES -> json.encodeToString(store.lines(request.getString("id").orEmpty(), request.getInt("offset")))
                        TranslationLibraryContract.FAVORITE -> { check(store.favorite(request.getString("id").orEmpty(), request.getBoolean("favorite")) > 0) { "记录已不存在" }; "ok" }
                        TranslationLibraryContract.DELETE -> store.delete(request.getStringArrayList("ids").orEmpty(), request.getStringArrayList("groups").orEmpty()).toString()
                        TranslationLibraryContract.MUSIC_LOCATION -> {
                            val (title, artist) = store.songIdentity(request.getString("id").orEmpty())
                            json.encodeToString(TranslationMusicLocator.locate(context, title, artist))
                        }
                        else -> error("不支持的操作")
                    }
                    result.putString("result", encoded)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { result.putString("error", e.message ?: "数据库操作失败") }
                runCatching { reply.send(Message.obtain().apply { data = result }) }
            }
        }
        true
    })
}
