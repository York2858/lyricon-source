package io.github.proify.lyricon.app.ai.translation

import android.content.Context
import android.os.*
import androidx.core.os.BundleCompat
import io.github.proify.lyricon.app.bridge.LyriconBridge
import io.github.proify.lyricon.common.PackageNames
import io.github.proify.lyricon.lyric.ai.translate.*
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal interface TranslationLibraryRepository {
    suspend fun list(query: TranslationLibraryQuery): TranslationLibraryPage
    suspend fun versions(group: String, offset: Int, status: String = ""): TranslationVersionPage
    suspend fun lines(id: String, offset: Int): TranslationLinePage
    suspend fun favorite(id: String, value: Boolean)
    suspend fun delete(ids: List<String>, groups: List<String>, status: String = ""): Int
    suspend fun musicLocation(id: String): TranslationMusicLocation = TranslationMusicLocation()
    suspend fun lineRecordSummary(): TranslationLineRecordSummary = error("当前数据库不支持清理逐行记录，请更新模块并重启系统界面")
    suspend fun deleteAllLineRecords(): Int = error("当前数据库不支持清理逐行记录，请更新模块并重启系统界面")
}

internal class SystemTranslationLibrary(private val context: Context) : TranslationLibraryRepository {
    private var endpoint: Messenger? = null
    private val json = TranslationLibraryContract.json

    private suspend fun request(what: Int, payload: Bundle): String {
        try {
            val service = endpoint ?: LyriconBridge.with(context).to(PackageNames.SYSTEM_UI)
                .key(TranslationLibraryContract.ROUTE).await(4000).let {
                    BundleCompat.getParcelable(it, "endpoint", Messenger::class.java)
                }?.also { endpoint = it }
                ?: error("暂时无法连接翻译数据库。更新模块后需重启系统界面或手机，再点击重试。")
            val result = withTimeout(10000) {
                suspendCancellableCoroutine<Bundle> { continuation ->
                    val receiver = Messenger(Handler(Looper.getMainLooper()) { message ->
                        if (context.packageManager.getPackagesForUid(message.sendingUid)
                                ?.contains(PackageNames.SYSTEM_UI) == true && continuation.isActive)
                            continuation.resume(Bundle(message.data))
                        true
                    })
                    try { service.send(Message.obtain(null, what).apply { data = payload; replyTo = receiver }) }
                    catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
                }
            }
            result.getString("error")?.let { error(it) }
            return result.getString("result") ?: error("数据库未返回有效结果，请刷新后重试")
        } catch (e: TimeoutCancellationException) {
            endpoint = null
            error("读取数据库超时，请重试；若刚更新模块，请先重启系统界面或手机。")
        } catch (e: DeadObjectException) {
            endpoint = null
            error("系统界面已重启，请重新加载数据库")
        }
    }
    override suspend fun list(query: TranslationLibraryQuery) = json.decodeFromString<TranslationLibraryPage>(
        request(TranslationLibraryContract.LIST, Bundle().apply { putString("query", json.encodeToString(query)) }))
    override suspend fun versions(group: String, offset: Int, status: String) = json.decodeFromString<TranslationVersionPage>(
        request(TranslationLibraryContract.VERSIONS, Bundle().apply { putString("group", group); putInt("offset", offset); putString("status", status) }))
    override suspend fun lines(id: String, offset: Int) = json.decodeFromString<TranslationLinePage>(
        request(TranslationLibraryContract.LINES, Bundle().apply { putString("id", id); putInt("offset", offset) }))
    override suspend fun favorite(id: String, value: Boolean) {
        request(TranslationLibraryContract.FAVORITE, Bundle().apply { putString("id", id); putBoolean("favorite", value) })
    }
    override suspend fun delete(ids: List<String>, groups: List<String>, status: String) = request(TranslationLibraryContract.DELETE,
        Bundle().apply { putStringArrayList("ids", ArrayList(ids)); putStringArrayList("groups", ArrayList(groups)); putString("status", status) }).toInt()
    override suspend fun musicLocation(id: String) = json.decodeFromString<TranslationMusicLocation>(
        request(TranslationLibraryContract.MUSIC_LOCATION, Bundle().apply { putString("id", id) }))
    override suspend fun lineRecordSummary() = json.decodeFromString<TranslationLineRecordSummary>(
        request(TranslationLibraryContract.LINE_RECORD_SUMMARY, Bundle()))
    override suspend fun deleteAllLineRecords() = request(TranslationLibraryContract.DELETE_LINE_RECORDS, Bundle()).toInt()
}
