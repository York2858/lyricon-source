package io.github.proify.lyricon.xposed.systemui.hook

import android.app.KeyguardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.proify.lyricon.app.bridge.FavoriteTrack
import io.github.proify.lyricon.subscriber.util.ScreenStateMonitor
import io.github.proify.lyricon.xposed.systemui.hook.OplusMediaFavoriteHooker.FavoriteButton
import io.github.proify.lyricon.xposed.systemui.hook.OplusMediaFavoriteHooker.currentTrack
import io.github.proify.lyricon.xposed.systemui.hook.OplusMediaFavoriteHooker.isFavoriteAction
import java.lang.ref.WeakReference
import java.lang.reflect.Executable
import java.util.WeakHashMap
import kotlin.math.roundToInt

/** ColorOS 17 renders its lockscreen Live Alert media card in the SystemUI plugin. */
internal object OplusLockscreenMediaFavoriteHooker {
    private const val TAG = "OplusLockMediaFavorite"
    private const val PLUGIN = "com.oplus.systemui.plugins"
    private const val SECTION = "$PLUGIN.shared.template.section.media."
    private const val MODEL = "$PLUGIN.shared.data.media.model."
    private val main = Handler(Looper.getMainLooper())
    private val installed = WeakHashMap<ClassLoader, Boolean>()
    @Volatile private var mediaPluginLoader: ClassLoader? = null

    fun initialize(module: XposedModule, loader: ClassLoader, context: Context) {
        runCatching {
            val host = loader.loadClass("com.oplus.systemui.media.seedling.OplusSeedlingMediaDataCombineLatest")
            val media = loader.loadClass("com.oplus.systemui.seedlingservice.mediaControl.SeedlingMediaData")
            val bridge = Cards(context)
            val getId = media.getMethod("getPlayerId")
            val getPackage = media.getMethod("getPackageName")
            val getToken = media.getMethod("getToken")
            val getSong = media.getMethod("getSong")
            val getArtist = media.getMethod("getArtist")
            val loaded = host.getDeclaredMethod("onMediaDataLoaded", String::class.java, media, Int::class.javaPrimitiveType)
            val removed = host.getDeclaredMethod("onMediaDataRemoved", String::class.java)
            val wrapper = loader.loadClass("com.android.systemui.shared.plugins.PluginInstance\$PluginContextWrapper")
            val keyguard = loader.loadClass("com.android.systemui.statusbar.policy.KeyguardStateControllerImpl")
            val notifyKeyguard = keyguard.getDeclaredMethod(
                "notifyKeyguardState", Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType
            )

            module.after(loaded) { _, args, _ ->
                val key = args[0] as? String ?: return@after
                val data = args[1] ?: return@after
                val id = getId.invoke(data) as? String
                val pkg = getPackage.invoke(data) as? String
                val token = getToken.invoke(data) as? MediaSession.Token
                val entry = if (!id.isNullOrEmpty() && !pkg.isNullOrEmpty() && token != null) {
                    Session(id, pkg, getSong.invoke(data)?.toString().orEmpty(), getArtist.invoke(data)?.toString().orEmpty(), token)
                } else null
                bridge.onMain { bridge.update(key, entry) }
            }
            module.after(removed) { _, args, _ ->
                val key = args[0] as? String ?: return@after
                bridge.onMain { bridge.update(key, null) }
            }
            module.after(notifyKeyguard) { _, args, _ ->
                val showing = args[0] == true && args[1] == false
                bridge.onMain { bridge.setKeyguardShowing(showing) }
            }

            module.after(wrapper.getDeclaredMethod("getClassLoader")) { _, _, result ->
                val pluginLoader = result as? ClassLoader ?: return@after
                if (pluginLoader !== loader && pluginLoader !== mediaPluginLoader) installPlugin(module, pluginLoader, bridge)
            }
            installPlugin(module, loader, bridge)
            ScreenStateMonitor.addListener(bridge)
            Log.i(TAG, "Lockscreen media session hooks installed")
        }.onFailure { Log.w(TAG, "Cannot install lockscreen media session hooks", it) }
    }

    private fun installPlugin(module: XposedModule, loader: ClassLoader, cards: Cards) {
        synchronized(installed) {
            if (installed.containsKey(loader)) return
            // Mark the loader before looking up plugin classes: some loaders ask their context for it again.
            installed[loader] = false
            val adapter = runCatching { loader.loadClass(SECTION + "b1") }.getOrNull() ?: return
            runCatching {
                val holder = loader.loadClass(SECTION + "c1")
                val section = loader.loadClass(SECTION + "s1")
                val cardModel = loader.loadClass(MODEL + "k")
                val data = loader.loadClass(MODEL + "u")
                val info = loader.loadClass(MODEL + "t")
                val action = loader.loadClass(MODEL + "o")
                val holderSection = holder.field("b")
                val holderModel = holder.field("d")
                val coverField = section.field("r")
                val leftField = section.field("v")
                val speakerField = section.field("w")
                val previousField = section.field("x")
                val cardData = cardModel.field("c")
                val mediaInfo = data.field("a")
                val uniqueId = info.field("a")
                val title = info.field("h")
                val artist = info.field("i")
                val pkg = info.field("m")
                val actions = info.field("n")
                val actionName = action.field("a")
                val actionLabel = action.field("g")
                val nextAction = action.field("i")
                fun read(holderObject: Any): Card? {
                    val model = holderModel.get(holderObject) ?: return null
                    val payload = cardData.get(model) ?: return null
                    val song = mediaInfo.get(payload) ?: return null
                    val id = (uniqueId.get(song) as? String)?.takeIf { it.isNotEmpty() } ?: return null
                    val player = (pkg.get(song) as? String)?.takeIf { it.isNotEmpty() } ?: return null
                    val hasHeart = (actions.get(song) as? Iterable<*>)?.any { item ->
                        if (!action.isInstance(item)) return@any false
                        fun isHeart(value: Any) = isFavoriteAction(
                            actionName.get(value)?.toString().orEmpty(), actionLabel.get(value)?.toString().orEmpty()
                        )
                        isHeart(item!!) || nextAction.get(item)?.takeIf { action.isInstance(it) }?.let(::isHeart) == true
                    } == true
                    return Card(id, player, title.get(song)?.toString().orEmpty(), artist.get(song)?.toString().orEmpty(), hasHeart)
                }
                val bind = adapter.declaredMethods.single { it.name == "onBindViewHolder" && it.parameterCount == 2 }
                val recycle = adapter.declaredMethods.single { it.name == "onViewRecycled" && it.parameterCount == 1 }
                module.after(bind) { _, args, _ ->
                    val viewHolder = args.firstOrNull()?.takeIf { holder.isInstance(it) } ?: return@after
                    val sectionObject = holderSection.get(viewHolder)
                    val cover = sectionObject?.let { coverField.get(it) } as? View
                    val controls = sectionObject?.let {
                        val left = leftField.get(it) as? View ?: return@let null
                        val speaker = speakerField.get(it) as? View ?: return@let null
                        val previous = previousField.get(it) as? View ?: return@let null
                        Controls(left, speaker, previous)
                    }
                    cards.onMain { cards.bind(viewHolder, cover, controls) { read(viewHolder) } }
                }
                module.after(recycle) { _, args, _ ->
                    args.firstOrNull()?.let { viewHolder -> cards.onMain { cards.remove(viewHolder) } }
                }
                // ColorOS applies its action visibility after binding the holder.
                val updateAction = section.getDeclaredMethod("H", leftField.type, action, Boolean::class.javaPrimitiveType)
                module.after(updateAction) { owner, args, _ ->
                    val left = args.firstOrNull() as? View ?: return@after
                    if (owner != null && left === leftField.get(owner)) {
                        cards.onMain { cards.onLeftActionChanged(left) }
                    }
                }
                installed[loader] = true
                mediaPluginLoader = loader
                Log.i(TAG, "Lockscreen media card binding hook installed")
            }.onFailure { Log.w(TAG, "Cannot install lockscreen media card binding hook", it) }
        }
    }

    private fun Class<*>.field(name: String) = getDeclaredField(name).apply { isAccessible = true }

    private fun XposedModule.after(method: Executable, block: (Any?, List<Any?>, Any?) -> Unit) {
        hook(method).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val result = chain.proceed()
                runCatching { block(chain.thisObject, chain.args, result) }
                    .onFailure { Log.w(TAG, "Cannot update lockscreen media card", it) }
                return result
            }
        })
    }

    private data class Card(val id: String, val player: String, val title: String, val artist: String, val hasHeart: Boolean)
    private data class Session(val id: String, val player: String, val title: String, val artist: String, val token: MediaSession.Token)
    private data class Selection(val card: Card, val token: MediaSession.Token, val track: FavoriteTrack)
    private data class Controls(val left: View, val speaker: View, val previous: View)

    private class Cards(private val context: Context) : ScreenStateMonitor.ScreenStateListener {
        private var showing = context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        private var screenOn = ScreenStateMonitor.state == ScreenStateMonitor.ScreenState.ON
        private val active get() = showing && screenOn
        private val sessions = mutableMapOf<String, Session>()
        private val holders = WeakHashMap<Any, WeakReference<Binding>>()
        private val leftActions = WeakHashMap<View, WeakReference<Binding>>()

        fun onMain(block: () -> Unit) {
            if (Looper.myLooper() == Looper.getMainLooper()) block()
            else main.post { runCatching(block).onFailure { Log.w(TAG, "Cannot refresh lockscreen media", it) } }
        }

        fun update(key: String, session: Session?) {
            val previous = if (session == null) sessions.remove(key) else sessions.put(key, session)
            if (!active || previous == session) return
            holders.values.forEach { ref ->
                val binding = ref.get() ?: return@forEach
                val cardId = binding.card?.id ?: return@forEach
                if (cardId == previous?.id || cardId == session?.id) binding.refresh()
            }
        }

        fun bind(holder: Any, cover: View?, controls: Controls?, read: () -> Card?) {
            val old = holders[holder]?.get()
            if (cover == null || controls == null) {
                remove(holder)
            } else if (old?.cover === cover) {
                old.refresh(readCard = true)
            } else {
                remove(holder)
                val binding = Binding(cover, controls, read)
                val ref = WeakReference(binding)
                holders[holder] = ref
                leftActions[controls.left] = ref
                binding.start()
            }
        }

        fun remove(holder: Any) {
            val binding = holders.remove(holder)?.get() ?: return
            leftActions.remove(binding.controls.left)
            binding.dispose()
        }

        fun onLeftActionChanged(left: View) {
            if (active) leftActions[left]?.get()?.updatePlacement()
        }

        fun setKeyguardShowing(value: Boolean) = setActiveState(value, screenOn)
        override fun onScreenOn() = onMain { setActiveState(showing, true) }
        override fun onScreenOff() = onMain { setActiveState(showing, false) }
        override fun onScreenUnlocked() = onMain { setActiveState(false, true) }

        private fun setActiveState(newShowing: Boolean, newScreenOn: Boolean) {
            val wasActive = active
            showing = newShowing
            screenOn = newScreenOn
            if (wasActive == active) return
            holders.values.forEach { it.get()?.refresh(readCard = active) }
        }

        private fun find(card: Card): Session? {
            // Live Alert's wire uniqueId comes from SeedlingMediaData.playerId.
            var match: Session? = null
            for (session in sessions.values) {
                if (session.id != card.id || session.player != card.player ||
                    session.title != card.title || session.artist != card.artist
                ) continue
                if (match != null && match.token != session.token) return null
                match = session
            }
            return match
        }

        private inner class Binding(val cover: View, val controls: Controls, private val read: () -> Card?) :
            View.OnAttachStateChangeListener {
            var card: Card? = null
                private set
            private var button: FavoriteButton? = null
            private var placement: Placement? = null
            private var selection: Selection? = null
            private var controller: MediaController? = null
            private var pendingController: MediaController? = null
            private val pendingMetadata = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) = refresh()
                override fun onSessionDestroyed() {
                    stopWaitingForMetadata()
                    clear()
                }
            }

            fun start() {
                cover.addOnAttachStateChangeListener(this)
                if (cover.isAttachedToWindow) onViewAttachedToWindow(cover)
            }

            override fun onViewAttachedToWindow(v: View) = refresh(readCard = true)

            override fun onViewDetachedFromWindow(v: View) {
                stopWaitingForMetadata()
                clear()
                card = null
                controller = null
            }

            fun dispose() {
                onViewDetachedFromWindow(cover)
                cover.removeOnAttachStateChangeListener(this)
                button?.let { (it.parent as? ViewGroup)?.removeView(it) }
                placement?.removeExtra()
                button = null
                placement = null
            }

            private fun clear() {
                if (selection != null) button?.bind(null)
                selection = null
            }

            private fun hasNativeHeart(media: MediaController): Boolean =
                media.playbackState?.customActions.orEmpty().any { isFavoriteAction(it.action, it.name.toString()) }

            private fun stopWaitingForMetadata() {
                pendingController?.unregisterCallback(pendingMetadata)
                pendingController = null
            }

            private fun select(): Selection? {
                val card = card?.takeUnless { it.hasHeart }
                val source = card?.let(::find)
                if (card == null || source == null) {
                    stopWaitingForMetadata()
                    return null
                }
                val media = controller?.takeIf { it.sessionToken == source.token }
                    ?: MediaController(context, source.token).also { controller = it }
                if (media.packageName != card.player || hasNativeHeart(media)) {
                    stopWaitingForMetadata()
                    return null
                }
                fun matchingTrack() = media.currentTrack()?.takeIf {
                    it.title == card.title && (card.artist.isEmpty() || it.artist == card.artist)
                }
                var track = matchingTrack()
                if (track == null && pendingController !== media) {
                    // During a track change, the card can arrive before the session metadata.
                    stopWaitingForMetadata()
                    media.registerCallback(pendingMetadata, main)
                    pendingController = media
                    track = matchingTrack()
                }
                if (track == null) return null
                stopWaitingForMetadata()
                return Selection(card, source.token, track)
            }

            fun refresh(readCard: Boolean = false) {
                runCatching {
                    if (!active || !cover.isAttachedToWindow) {
                        if (readCard) card = null
                        stopWaitingForMetadata()
                        return clear()
                    }
                    if (readCard || card == null) card = read()
                    val next = select()
                    if (next == null) return clear()
                    val moved = button?.let { placement?.place(it) } == true
                    if (next == selection && !moved) return
                    val heart = button ?: addButton() ?: return
                    selection = next
                    heart.bind(controller, next.track)
                }.onFailure {
                    stopWaitingForMetadata()
                    clear()
                    Log.w(TAG, "Cannot bind lockscreen favorite", it)
                }
            }

            fun updatePlacement() {
                val heart = button ?: return
                val bound = selection ?: return
                if (placement?.place(heart) == true) heart.bind(controller, bound.track)
            }

            private fun addButton(): FavoriteButton? {
                val (nativeLeft, speaker, previous) = controls
                val slot = nativeLeft.parent as? FrameLayout ?: return null
                val row = slot.parent as? LinearLayout ?: return null
                val core = previous.parent?.parent as? View ?: return null
                val speakerSlot = speaker.parent as? FrameLayout ?: return null
                if (core.parent !== row || speakerSlot.parent !== row) return null
                val size = speakerSlot.layoutParams.width.takeIf { it > 0 } ?: return null
                val iconBox = speaker.layoutParams.width.takeIf { it > 0 } ?: return null
                // The native animation and the heart vector have different internal padding.
                val padding = ((size - iconBox * 0.71f) / 2f).roundToInt().coerceAtLeast(0)
                val position = Placement(row, core, slot, nativeLeft, size)
                placement = position
                return FavoriteButton(
                    cover.context, context,
                    isCurrent = { media ->
                        val bound = selection
                        active && cover.isAttachedToWindow && bound != null && media.sessionToken == bound.token
                    },
                    logTag = TAG,
                    onAvailabilityChanged = position::setAvailable,
                ) { ColorStateList.valueOf(Color.WHITE) }.apply {
                    setPadding(padding, padding, padding, padding)
                    position.place(this)
                    button = this
                }
            }
        }
    }

    private class Placement(
        private val row: LinearLayout,
        private val core: View,
        private val slot: FrameLayout,
        private val nativeLeft: View,
        private val size: Int,
    ) {
        private var extra: FrameLayout? = null
        private var available = false

        fun place(button: View): Boolean {
            // Reuse the empty outer slot, or leave the native action in place
            // and put the heart between it and the playback group.
            val target = if (nativeLeft.visibility != View.VISIBLE && slot.visibility == View.VISIBLE) {
                slot
            } else {
                extra ?: FrameLayout(row.context).also {
                    row.addView(it, row.indexOfChild(core), LinearLayout.LayoutParams(
                        if (available) size else 0, size
                    ).apply { gravity = Gravity.CENTER_VERTICAL })
                    extra = it
                }
            }
            if (button.parent === target) return false
            (button.parent as? ViewGroup)?.removeView(button)
            target.addView(button, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
            if (target === slot) removeExtra()
            return true
        }

        fun setAvailable(value: Boolean) {
            available = value
            extra?.let { frame ->
                val params = frame.layoutParams
                val width = if (value) size else 0
                if (params.width != width) {
                    params.width = width
                    frame.layoutParams = params
                }
            }
        }

        fun removeExtra() {
            extra?.let(row::removeView)
            extra = null
        }
    }
}
