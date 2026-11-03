package io.github.proify.lyricon.app.activity.lyric.immersive

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.ParcelFileDescriptor
import io.github.proify.lyricon.app.LyriconApp
import io.github.proify.lyricon.app.bridge.ImmersiveAppearanceContract as Contract
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal object ImmersiveAppearanceSync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var exportedUri: String? = null
    private var exportedService: Any? = null
    private var exportedVersion = ""
    private val generation = java.util.concurrent.atomic.AtomicLong()

    fun publish(context: Context, values: Map<String, *>) {
        val app = context.applicationContext as? LyriconApp ?: return
        val snapshot = values.toMap()
        val request = generation.incrementAndGet()
        scope.launch { lock.withLock {
            if (request != generation.get()) return@withLock
            val service = LyriconApp.xposedService ?: return@withLock
            runCatching {
                val remote = service.getRemotePreferences(Contract.PREFERENCES)
                val edit = remote.edit()
                Contract.KEYS.forEach { key -> when(val value = snapshot[key]) {
                    is Boolean -> edit.putBoolean(key, value)
                    is Number -> edit.putInt(key, value.toInt())
                    is String -> edit.putString(key, value)
                    else -> edit.remove(key)
                } }
                val uri = snapshot["custom_background_uri"] as? String ?: ""
                if ((snapshot["background_mode"] as? Number)?.toInt() == 4 && uri.isNotBlank() && (uri != exportedUri || service !== exportedService)) runCatching {
                    runCatching { app.grantUriPermission("com.android.systemui", Uri.parse(uri), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(app.contentResolver, Uri.parse(uri))) { decoder, info, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        val ratio = minOf(1f, 1400f / maxOf(info.size.width, info.size.height))
                        decoder.setTargetSize((info.size.width * ratio).toInt().coerceAtLeast(1), (info.size.height * ratio).toInt().coerceAtLeast(1))
                    }
                    try {
                        ParcelFileDescriptor.AutoCloseOutputStream(service.openRemoteFile(Contract.CUSTOM_FILE)).use {
                            it.channel.truncate(0)
                            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                        }
                    } finally { bitmap.recycle() }
                    exportedVersion = java.util.UUID.randomUUID().toString()
                    exportedUri = uri; exportedService = service
                }.onFailure { edit.remove(Contract.CUSTOM_VERSION); android.util.Log.w("ImmersiveSettings", "Cannot share custom background file", it) }
                if (uri == exportedUri && service === exportedService) edit.putString(Contract.CUSTOM_VERSION, exportedVersion)
                if (uri.isBlank()) edit.remove(Contract.CUSTOM_VERSION)
                if (request == generation.get()) check(edit.commit())
            }.onFailure { android.util.Log.w("ImmersiveSettings", "Cannot share media background", it) }
        } }
    }
}
