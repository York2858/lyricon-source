package io.github.proify.lyricon.xposed.systemui.lyric

import android.app.Notification
import android.content.Context
import android.graphics.drawable.Icon
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import io.github.proify.lyricon.common.util.CoverImages
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.proify.lyricon.xposed.systemui.util.MediaTrackMeta
import io.github.proify.lyricon.xposed.systemui.util.NotificationCoverHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal object ImmersiveNotificationCover {
    const val BUILD_ID = "immersive-v59-neutron-4"
    data class Cover(val notificationKey: String, val title: String, val key: String, val bytes: ByteArray)

    private val initialized = AtomicBoolean()
    private val covers = ConcurrentHashMap<String, Cover>()
    private val workers = ConcurrentHashMap<String, CoverWorker>()
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
        val icon = NotificationArtwork.icon(item.notification) ?: return
        workers.computeIfAbsent(item.packageName, ::CoverWorker)
            .enqueue(context, CoverRequest(item.key, title, icon))
    }

    private data class CoverRequest(val notificationKey: String, val title: String, val icon: Icon)

    private data class PersistedCover(val key: String, val fileSignature: String)

    private class CoverWorker(private val packageName: String) {
        private var active: CoverRequest? = null
        private var pending: CoverRequest? = null
        private var lastCover: Cover? = null
        private var lastIcon: WeakReference<Icon>? = null
        private var lastPixels: ByteArray? = null
        private var persisted: PersistedCover? = null

        @Synchronized
        fun enqueue(context: Context, request: CoverRequest) {
            if ((pending ?: active) == request) return
            if (active == null) {
                active = request
                scope.launch { drain(context, request) }
            } else {
                pending = request
            }
        }

        @Synchronized
        private fun nextRequest(): CoverRequest? {
            active = pending
            pending = null
            return active
        }

        private fun drain(context: Context, first: CoverRequest) {
            var request: CoverRequest? = first
            while (request != null) {
                val current = request
                runCatching { process(context, current) }.onFailure {
                    Log.w("ImmersiveCover", "Cannot process media notification artwork for $packageName", it)
                }
                request = nextRequest()
            }
        }

        private fun process(context: Context, request: CoverRequest) {
            val previous = lastCover
            val cover = if (previous != null && lastIcon?.get() === request.icon) {
                previous.copy(notificationKey = request.notificationKey, title = request.title)
            } else {
                val bitmap = NotificationArtwork.render(context, request.icon) ?: return
                try {
                    val pixels = ByteBuffer.allocate(Int.SIZE_BYTES * 2 + bitmap.byteCount)
                    pixels.putInt(bitmap.width)
                    pixels.putInt(bitmap.height)
                    bitmap.copyPixelsToBuffer(pixels)
                    val fingerprint = MessageDigest.getInstance("SHA-256").digest(pixels.array())
                    val result = if (previous != null && lastPixels?.contentEquals(fingerprint) == true) {
                        previous.copy(notificationKey = request.notificationKey, title = request.title)
                    } else {
                        val bytes = NotificationArtwork.encode(bitmap)
                        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
                            .joinToString("") { "%02x".format(it) }
                        Log.i("ImmersiveCover", "cached package=$packageName bytes=${bytes.size}")
                        Cover(request.notificationKey, request.title, "notification:$packageName:$hash", bytes)
                    }
                    lastPixels = fingerprint
                    result
                } finally {
                    bitmap.recycle()
                }
            }
            lastIcon = WeakReference(request.icon)
            lastCover = cover
            synchronized(this) {
                if (pending != null) return
                covers[packageName] = cover
            }

            val meta = MediaTrackMeta.resolve(packageName) ?: return
            val title = meta.title ?: return
            if (!title.trim().equals(request.title.trim(), ignoreCase = true)) return
            val file = NotificationCoverHelper.getCachedCoverFile(packageName, title, meta.artist.orEmpty()) ?: return
            persist(file, cover)
        }

        private fun persist(file: File, cover: Cover) {
            if (persisted == PersistedCover(cover.key, CoverImages.key(file))) return
            file.parentFile?.mkdirs()
            val temporary = File.createTempFile("immersive-cover-", ".tmp", file.parentFile)
            try {
                temporary.writeBytes(cover.bytes)
                java.nio.file.Files.move(temporary.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                persisted = PersistedCover(cover.key, CoverImages.key(file))
            } finally {
                temporary.delete()
            }
        }
    }
}
