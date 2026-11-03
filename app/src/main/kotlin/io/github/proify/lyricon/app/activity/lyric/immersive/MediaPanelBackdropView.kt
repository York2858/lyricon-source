package io.github.proify.lyricon.app.activity.lyric.immersive

import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.widget.FrameLayout
import androidx.compose.runtime.*
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.github.proify.lyricon.app.activity.lyric.immersive.halcyon.LocalPlayerSurfaceActive
import io.github.proify.lyricon.app.activity.lyric.immersive.halcyon.PlayerPalette
import kotlinx.coroutines.*

class MediaPanelBackdropView(context: Context) : FrameLayout(context) {
    private var cover by mutableStateOf<Bitmap?>(null)
    private var custom by mutableStateOf<Bitmap?>(null)
    private var preferences by mutableStateOf<Map<String, *>>(emptyMap<String, Any>())
    private var playing by mutableStateOf(false)
    private var position by mutableLongStateOf(0L)
    private var visible by mutableStateOf(false)
    private var owner = Owner()
    private val compose = ComposeView(context)
    private var compositionScope: CoroutineScope? = null
    private var recomposer: Recomposer? = null

    init {
        setViewTreeLifecycleOwner(owner)
        setViewTreeSavedStateRegistryOwner(owner)
        isClickable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        prepareComposition()
        addView(compose.apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                val palette = remember(cover) { PlayerPalette.from(cover) }
                CompositionLocalProvider(LocalPlayerSurfaceActive provides visible) {
                    HalcyonBackdrop(cover, palette, preferences, playing && visible, position, custom)
                }
            }
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun update(cover: Bitmap?, preferences: Map<String, *>, playing: Boolean, position: Long, custom: Bitmap? = null) {
        this.cover = cover; this.preferences = preferences.toMap(); this.playing = playing; this.position = position; this.custom = custom
    }

    override fun onAttachedToWindow() {
        if (owner.lifecycle.currentState == Lifecycle.State.DESTROYED) {
            owner = Owner(); setViewTreeLifecycleOwner(owner); setViewTreeSavedStateRegistryOwner(owner)
        }
        owner.registry.currentState = Lifecycle.State.RESUMED
        prepareComposition()
        visible = windowVisibility == View.VISIBLE
        super.onAttachedToWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        visible = visibility == View.VISIBLE && isAttachedToWindow
    }

    override fun onDetachedFromWindow() {
        visible = false
        super.onDetachedFromWindow()
        owner.registry.currentState = Lifecycle.State.DESTROYED
        recomposer?.cancel(); recomposer = null
        compositionScope?.cancel(); compositionScope = null
    }

    private fun prepareComposition() {
        if (recomposer != null) return
        val scope = CoroutineScope(AndroidUiDispatcher.Main + SupervisorJob())
        val next = Recomposer(scope.coroutineContext)
        compositionScope = scope; recomposer = next
        compose.setParentCompositionContext(next)
        scope.launch { next.runRecomposeAndApplyChanges() }
    }

    private class Owner : LifecycleOwner, SavedStateRegistryOwner {
        val registry = LifecycleRegistry(this)
        private val saved = SavedStateRegistryController.create(this)
        override val lifecycle get() = registry
        override val savedStateRegistry get() = saved.savedStateRegistry
        init { saved.performAttach(); saved.performRestore(null); registry.currentState = Lifecycle.State.CREATED }
    }
}
