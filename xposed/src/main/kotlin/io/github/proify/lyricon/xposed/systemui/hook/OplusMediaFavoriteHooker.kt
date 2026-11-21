package io.github.proify.lyricon.xposed.systemui.hook

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.RippleDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.os.Looper
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.proify.lyricon.app.bridge.FavoriteClient
import io.github.proify.lyricon.app.bridge.FavoriteTrack
import io.github.proify.lyricon.xposed.R
import io.github.proify.lyricon.xposed.systemui.lyric.control.ControlUi
import io.github.proify.lyricon.xposed.systemui.util.MediaTrackMeta
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal object OplusMediaFavoriteHooker {
    private const val TAG = "OplusMediaFavorite"
    private const val QS = "com.oplus.systemui.qs.media."
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val buttonId by lazy { View.generateViewId() }
    private val actionSeparators = Regex("[^a-z]+")
    private val favoriteActions = setOf("favorite", "favourite", "unfavorite", "like", "unlike", "heart")
    private val favoriteLabels = setOf(
        "收藏", "取消收藏", "已收藏", "喜欢", "取消喜欢", "喜爱", "取消喜爱",
        "like", "unlike", "favorite", "favourite", "add to favorites", "remove from favorites"
    )

    fun initialize(module: XposedModule, loader: ClassLoader) {
        val normal = runCatching { loader.loadClass(QS + "OplusQsNormalMediaPanelView") }.getOrNull() ?: return
        runCatching {
            val base = loader.loadClass(QS + "OplusQsBaseMediaPanelView")
            val data = loader.loadClass("com.android.systemui.media.controls.shared.model.MediaData")
            val bind = base.getDeclaredMethod("bindMediaDataInner", data, String::class.java)
            val unbind = base.getDeclaredMethod("unBindMediaData")
            val getToken = data.getMethod("getToken")
            val getActions = data.getMethod("getSemanticActions")
            val getExtension = getActions.returnType.getMethod("getMediaButtonEx")
            val getHeart = getExtension.returnType.getMethod("getHeartAction")
            module.hook(bind).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    val panel = chain.thisObject as? ViewGroup
                    val media = chain.args.firstOrNull()
                    if (panel != null && normal.isInstance(panel)) onPanelThread(panel) {
                        val token = media?.let { getToken.invoke(it) } as? MediaSession.Token
                        val heart = media?.let { getActions.invoke(it) }?.let { getExtension.invoke(it) }?.let { getHeart.invoke(it) }
                        val button = panel.findViewById<FavoriteButton>(buttonId)
                        if (token == null || heart != null) {
                            button?.bind(null)
                        } else {
                            val controller = button?.controller?.takeIf { it.sessionToken == token }
                                ?: MediaController(panel.context, token)
                            val actions = controller.playbackState?.customActions.orEmpty()
                            if (actions.any { isFavoriteAction(it.action, it.name.toString()) }) button?.bind(null)
                            else (button ?: addButton(panel))?.bind(controller)
                        }
                    }
                    return result
                }
            })
            module.hook(unbind).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    val panel = chain.thisObject as? ViewGroup
                    if (panel != null && normal.isInstance(panel)) onPanelThread(panel) {
                        panel.findViewById<FavoriteButton>(buttonId)?.bind(null)
                    }
                    return result
                }
            })
            Log.i(TAG, "ColorOS quick settings media hook installed")
        }.onFailure { Log.w(TAG, "Cannot install media card hook", it) }
    }

    private fun onPanelThread(panel: View, action: () -> Unit) {
        val apply = { runCatching(action).onFailure { Log.w(TAG, "Cannot bind media favorite", it) }; Unit }
        if (Looper.myLooper() == Looper.getMainLooper()) apply() else panel.post { apply() }
    }

    internal fun isFavoriteAction(action: String, label: String): Boolean {
        return label.trim().lowercase() in favoriteLabels ||
            actionSeparators.splitToSequence(action.lowercase()).any { it in favoriteActions }
    }

    private fun addButton(panel: ViewGroup): FavoriteButton? {
        fun child(name: String): View? {
            val id = panel.resources.getIdentifier(name, "id", "com.android.systemui")
            return if (id == 0) null else panel.findViewById(id)
        }
        val output = child("oplus_media_output_switch_btn") as? ImageView ?: return null
        val cover = child("oplus_media_cover_image") ?: return null
        val parent = output.parent as? ViewGroup ?: return null
        if (cover.parent !== parent) return null
        val density = panel.resources.displayMetrics.density
        val size = (32 * density).roundToInt()
        val type = output.layoutParams.javaClass
        val params = type.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .newInstance(size, size) as ViewGroup.MarginLayoutParams
        type.getField("endToStart").setInt(params, output.id)
        type.getField("topToTop").setInt(params, output.id)
        type.getField("bottomToBottom").setInt(params, output.id)
        params.marginEnd = (4 * density).roundToInt()
        val title = child("oplus_media_panel_title") as? TextView
        return FavoriteButton(panel.context) {
            output.imageTintList ?: ColorStateList.valueOf(title?.currentTextColor ?: Color.WHITE)
        }.apply {
            id = buttonId
            val padding = (5 * density).roundToInt()
            setPadding(padding, padding, padding, padding)
            parent.addView(this, params)
        }
    }

    internal class FavoriteButton(
        context: Context,
        private val clientContext: Context = context,
        private val isCurrent: (MediaController) -> Boolean = { true },
        private val logTag: String = TAG,
        private val onAvailabilityChanged: (Boolean) -> Unit = {},
        private val retryReadAfterMs: Long = 0L,
        private val tint: () -> ColorStateList,
    ) : ImageButton(context) {
        private val icons = ControlUi.moduleResources(context)
        var controller: MediaController? = null
            private set
        private var track: FavoriteTrack? = null
        private var job: Job? = null
        private var writing = false
        private var visibleInWindow = false

        init {
            scaleType = ScaleType.FIT_CENTER
            background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null,
                android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(Color.WHITE)
                })
            hide()
            setOnClickListener { update(toggle = true) }
        }

        fun bind(controller: MediaController?, next: FavoriteTrack? = controller?.currentTrack()) {
            if (this.controller?.sessionToken != controller?.sessionToken || track != next) {
                job?.cancel()
                job = null
                writing = false
                hide()
            }
            this.controller = controller
            track = next
            if (visibleInWindow) update()
        }

        override fun onVisibilityAggregated(isVisible: Boolean) {
            super.onVisibilityAggregated(isVisible)
            if (visibleInWindow == isVisible) return
            visibleInWindow = isVisible
            if (isVisible) update() else if (!writing) { job?.cancel(); job = null }
        }

        override fun onDetachedFromWindow() {
            visibleInWindow = false
            job?.cancel()
            job = null
            writing = false
            super.onDetachedFromWindow()
        }

        private fun update(toggle: Boolean = false) {
            val session = controller ?: return
            val current = track ?: return
            if (!visibleInWindow || writing || (!toggle && job?.isActive == true)) return
            if (!isCurrent(session)) return hide()
            if (toggle && session.currentTrack() != current) return hide()
            job?.cancel()
            writing = toggle
            if (toggle) isEnabled = false
            job = scope.launch {
                try {
                    val reply = if (toggle) FavoriteClient.toggle(clientContext, current) else readFavorite(session, current)
                    if (controller?.sessionToken != session.sessionToken || track != current ||
                        session.currentTrack() != current || !isCurrent(session)
                    ) return@launch
                    check(reply.getBoolean("accepted")) { reply.getString("error") ?: "喜爱记录不可用" }
                    val favorite = reply.getBoolean("favorite")
                    val res = if (favorite) R.drawable.sui_control_favorite_filled else R.drawable.sui_control_favorite
                    setImageDrawable(icons.getDrawable(res, null).mutate())
                    imageTintList = tint()
                    contentDescription = if (favorite) "取消喜爱" else "喜爱"
                    tooltipText = contentDescription
                    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
                    setAvailable(true)
                    isClickable = true
                    isFocusable = true
                    isEnabled = true
                    if (toggle) Log.i(logTag, "Favorite updated: player=${current.player}, favorite=$favorite")
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    if (toggle) {
                        isEnabled = true
                        Toast.makeText(context, error.message ?: "喜爱记录保存失败", Toast.LENGTH_SHORT).show()
                    } else hide()
                } finally {
                    if (toggle) writing = false
                }
            }
        }

        private suspend fun readFavorite(session: MediaController, current: FavoriteTrack): Bundle {
            try {
                return FavoriteClient.request(clientContext, current)
            } catch (error: Exception) {
                if (error is CancellationException || retryReadAfterMs <= 0) throw error
                delay(retryReadAfterMs)
                if (!visibleInWindow || controller?.sessionToken != session.sessionToken ||
                    track != current || session.currentTrack() != current || !isCurrent(session)
                ) throw CancellationException()
                return FavoriteClient.request(clientContext, current)
            }
        }

        private fun hide() {
            setAvailable(false)
            isEnabled = false
            isClickable = false
            isFocusable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        private fun setAvailable(available: Boolean) {
            val nextAlpha = if (available) 1f else 0f
            if (alpha == nextAlpha) return
            alpha = nextAlpha
            onAvailabilityChanged(available)
        }
    }

    internal fun MediaController.currentTrack(): FavoriteTrack? {
        val metadata = metadata ?: return null
        val meta = MediaTrackMeta.extract(metadata)
        return FavoriteTrack(packageName, metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID).orEmpty(),
            meta.title ?: return null, meta.artist.orEmpty(), meta.album.orEmpty(), meta.durationMs ?: 0L)
    }
}
