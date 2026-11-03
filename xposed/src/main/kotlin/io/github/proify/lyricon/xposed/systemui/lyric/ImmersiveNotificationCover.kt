package io.github.proify.lyricon.xposed.systemui.lyric

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.proify.lyricon.xposed.systemui.util.MediaTrackMeta
import io.github.proify.lyricon.xposed.systemui.util.NotificationCoverHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal object ImmersiveNotificationCover {
    const val BUILD_ID = "immersive-v59-neutron-4"
    data class Cover(val notificationKey: String, val title: String, val key: String, val bytes: ByteArray)

    private val initialized = AtomicBoolean()
    private val covers = ConcurrentHashMap<String, Cover>()
    private val versions = ConcurrentHashMap<String, Long>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var activeListener: WeakReference<NotificationListenerService>? = null
    @Volatile private var applicationContext: Context? = null
    private var lastScanAt = 0L

    fun attach(context: Context) {
        applicationContext = context.applicationContext
        Log.i("ImmersiveCover", "attach context=${context.javaClass.name} application=${applicationContext?.javaClass?.name}")
    }

    fun initialize(module: XposedModule, loader: ClassLoader) {
        if (!initialized.compareAndSet(false, true)) return
        Log.i("ImmersiveCover", "initialize $BUILD_ID")
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
        // Hook each available callback independently. An OEM may inherit a callback or
        // override a different posted overload; one missing method must not disable both.
        val listenerClass = runCatching {
            loader.loadClass("com.android.systemui.statusbar.NotificationListener")
        }.onFailure {
            Log.w("ImmersiveCover", "Cannot find SystemUI notification listener", it)
        }.getOrNull() ?: NotificationListenerService::class.java
        listenerClass.declaredConstructors.forEach { constructor ->
            runCatching {
                module.hook(constructor).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val result = chain.proceed()
                        (chain.thisObject as? NotificationListenerService)?.let {
                            activeListener = WeakReference(it)
                            Log.i("ImmersiveCover", "listener created class=${it.javaClass.name}")
                        }
                        return result
                    }
                })
            }.onFailure { Log.w("ImmersiveCover", "Cannot capture listener constructor", it) }
        }
        generateSequence(listenerClass) { it.superclass }
            .takeWhile { NotificationListenerService::class.java.isAssignableFrom(it) }
            .flatMap { it.declaredMethods.asSequence() }
            .filter {
                (it.name == "onListenerConnected" && it.parameterCount == 0) ||
                    (it.name == "onNotificationPosted" && it.parameterTypes.firstOrNull() == StatusBarNotification::class.java)
            }.forEach { method ->
                runCatching {
                    module.hook(method).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            val result = chain.proceed()
                            val listener = chain.thisObject as? NotificationListenerService ?: return result
                            activeListener = WeakReference(listener)
                            runCatching {
                                if (method.name == "onNotificationPosted") {
                                    (chain.args.firstOrNull() as? StatusBarNotification)?.let(::onNotificationPosted)
                                } else {
                                    Log.i("ImmersiveCover", "listener connected class=${listener.javaClass.name}")
                                    listener.activeNotifications?.forEach(::onNotificationPosted)
                                }
                            }.onFailure { Log.w("ImmersiveCover", "Cannot read media notifications in ${method.name}", it) }
                            return result
                        }
                    })
                }.onFailure {
                    Log.w("ImmersiveCover", "Cannot attach media notification callback $method", it)
                }
        }
        scope.launch {
            repeat(3) { attempt ->
                delay(if (attempt == 0) 1_000 else 2_000)
                runCatching { captureActiveNotifications() }.onFailure { error ->
                    Log.w("ImmersiveCover", "Cannot capture current media notification artwork", error)
                }
            }
        }
    }

    fun find(packageName: String, title: String): Cover? =
        matching(covers[packageName], title) ?: runCatching {
            captureActiveNotifications()
            matching(covers[packageName], title)
        }.onFailure {
            Log.w("ImmersiveCover", "Cannot read active media notification artwork for $packageName", it)
        }.getOrNull()

    private fun matching(cover: Cover?, title: String): Cover? = cover?.takeIf {
        title.isBlank() || it.title.trim().equals(title.trim(), ignoreCase = true)
    }

    @Synchronized
    private fun captureActiveNotifications() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastScanAt < 5000) return
        lastScanAt = now
        // Registration and connection may precede module initialization on OEM SystemUI.
        // Recover its existing Dagger singleton instead of waiting for another callback.
        val listener = activeListener?.get() ?: ExistingNotificationListenerResolver.resolve(
            applicationContext,
            NotificationListenerService::class.java,
            { Log.i("ImmersiveCover", it) }
        )?.also {
            activeListener = WeakReference(it)
            Log.i("ImmersiveCover", "listener resolved source=component class=${it.javaClass.name}")
        }
        val notifications = listener?.activeNotifications.orEmpty()
        Log.i("ImmersiveCover", "scan listener=${listener != null} active=${notifications.size} cached=${covers.size}")
        notifications.forEach(::onNotificationPosted)
    }

    internal fun onNotificationPosted(item: StatusBarNotification) {
        // SystemUI constructs its listener with `new`, rather than attaching it as an
        // Android Service. Its ContextWrapper has no base context; use the Application.
        val context = applicationContext ?: return
        if (isMediaNotification(item.notification)) save(context, item)
    }

    private fun isMediaNotification(notification: Notification): Boolean =
        notification.category == Notification.CATEGORY_TRANSPORT ||
            notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)

    private fun save(context: Context, item: StatusBarNotification) {
        val title = item.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val icon = NotificationArtwork.icon(item.notification)
        Log.i("ImmersiveCover", "media package=${item.packageName} titleHash=${title.hashCode()} icon=${icon != null}")
        if (icon == null) return
        val version = versions.merge(item.packageName, 1L, Long::plus) ?: return
        scope.launch {
            runCatching {
                val bytes = NotificationArtwork.encode(context, icon) ?: return@runCatching
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
}
