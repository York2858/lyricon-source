package io.github.proify.lyricon.xposed.systemui.lyric

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Icon
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.lang.ref.WeakReference
import io.github.proify.lyricon.xposed.systemui.util.NotificationCoverHelper
import io.github.proify.lyricon.xposed.systemui.util.MediaTrackMeta

internal object ImmersiveNotificationCover {
    data class Cover(val notificationKey: String, val title: String, val key: String, val bytes: ByteArray)

    private val initialized = AtomicBoolean()
    private val covers = ConcurrentHashMap<String, Cover>()
    private val versions = ConcurrentHashMap<String, Long>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var activeListener: WeakReference<NotificationListenerService>? = null
    private var lastScanAt = 0L

    fun initialize(module: XposedModule, loader: ClassLoader, context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        Log.i("ImmersiveCover", "initialize cover-v59")
        NotificationListenerService::class.java.declaredMethods.filter { it.name == "registerAsSystemService" }.forEach { method ->
            runCatching {
                module.hook(method).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val result = chain.proceed()
                        (chain.thisObject as? NotificationListenerService)?.let {
                            activeListener = WeakReference(it)
                            Log.i("ImmersiveCover", "listener registered class=${it.javaClass.name}")
                        }
                        return result
                    }
                })
            }.onFailure { Log.w("ImmersiveCover", "registration hook failed", it) }
        }
        runCatching {
            val listener = loader.loadClass("com.android.systemui.statusbar.NotificationListener")
            val posted = listener.getDeclaredMethod(
                "onNotificationPosted",
                StatusBarNotification::class.java,
                NotificationListenerService.RankingMap::class.java
            )
            module.hook(posted).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    (chain.thisObject as? NotificationListenerService)?.let { activeListener = WeakReference(it) }
                    (chain.args[0] as? StatusBarNotification)?.let { update(context, it) }
                    return result
                }
            })
            val connected = listener.getDeclaredMethod("onListenerConnected")
            module.hook(connected).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    (chain.thisObject as? NotificationListenerService)?.let { activeListener = WeakReference(it) }
                    runCatching {
                        (chain.thisObject as? NotificationListenerService)?.activeNotifications?.forEach {
                            update(context, it)
                        }
                    }.onFailure {
                        Log.w("ImmersiveCover", "Cannot read initial media notifications", it)
                    }
                    return result
                }
            })
        }.onFailure {
            Log.w("ImmersiveCover", "Cannot attach media notification artwork listener", it)
        }
        scope.launch {
            repeat(3) { attempt ->
                delay(if (attempt == 0) 1_000 else 2_000)
                runCatching { captureActiveNotifications(context) }.onFailure { error ->
                    Log.w("ImmersiveCover", "Cannot capture current media notification artwork", error)
                }
            }
        }
    }

    fun find(context: Context, packageName: String, title: String): Cover? =
        matching(covers[packageName], title) ?: runCatching {
            captureActiveNotifications(context)
            matching(covers[packageName], title)
        }.onFailure {
            Log.w("ImmersiveCover", "Cannot read active media notification artwork for $packageName", it)
        }.getOrNull()

    private fun matching(cover: Cover?, title: String): Cover? = cover?.takeIf {
        title.isBlank() || it.title.trim().equals(title.trim(), ignoreCase = true)
    }

    @Synchronized
    private fun captureActiveNotifications(context: Context) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastScanAt < 5000) return
        lastScanAt = now
        val listener = activeListener?.get()
        val notifications = listener?.activeNotifications.orEmpty()
        Log.i("ImmersiveCover", "scan listener=${listener != null} active=${notifications.size} cached=${covers.size}")
        notifications.filter { isMediaNotification(it.notification) }.forEach { save(context, it) }
    }

    private fun update(context: Context, item: StatusBarNotification) {
        if (isMediaNotification(item.notification)) save(context, item)
    }

    private fun isMediaNotification(notification: Notification): Boolean =
        notification.category == Notification.CATEGORY_TRANSPORT ||
            notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)

    private fun save(context: Context, item: StatusBarNotification) {
        val title = item.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val icon = item.notification.getLargeIcon()
        Log.i("ImmersiveCover", "media package=${item.packageName} titleHash=${title.hashCode()} icon=${icon != null}")
        if (icon == null) return
        val version = versions.merge(item.packageName, 1L, Long::plus) ?: return
        scope.launch {
            runCatching {
                val bytes = encodeIcon(context, icon) ?: return@runCatching
                val hash = MessageDigest.getInstance("SHA-256")
                    .digest(bytes)
                    .joinToString("") { "%02x".format(it) }
                if (versions[item.packageName] == version) {
                    covers[item.packageName] = Cover(
                        item.key,
                        title,
                        "notification:${item.packageName}:$hash",
                        bytes
                    )
                    Log.i("ImmersiveCover", "cached package=${item.packageName} bytes=${bytes.size}")
                    val meta = MediaTrackMeta.resolve(item.packageName)
                    if (meta?.title?.trim().equals(title.trim(), ignoreCase = true)) {
                        NotificationCoverHelper.getCachedCoverFile(item.packageName, meta?.title.orEmpty(), meta?.artist.orEmpty())?.let { file ->
                            file.parentFile?.mkdirs()
                            val temporary = java.io.File.createTempFile("immersive-cover-", ".tmp", file.parentFile)
                            try {
                                temporary.writeBytes(bytes)
                                java.nio.file.Files.move(temporary.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                            } finally { temporary.delete() }
                        }
                    }
                }
            }.onFailure {
                Log.w("ImmersiveCover", "Cannot decode media notification artwork for ${item.packageName}", it)
            }
        }
    }

    private fun encodeIcon(context: Context, icon: Icon): ByteArray? {
        val drawable = icon.loadDrawable(context) ?: return null
        val intrinsicWidth = drawable.intrinsicWidth.takeIf { it > 0 } ?: 128
        val intrinsicHeight = drawable.intrinsicHeight.takeIf { it > 0 } ?: 128
        val scale = 256f / maxOf(intrinsicWidth, intrinsicHeight)
        val width = (intrinsicWidth * scale).toInt().coerceAtLeast(1)
        val height = (intrinsicHeight * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            drawable.setBounds(0, 0, width, height)
            drawable.draw(Canvas(bitmap))
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }
}
