/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.proify.lyricon.xposed.systemui.hook

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.proify.android.extensions.dp
import io.github.proify.lyricon.statusbarlyric.StatusBarLyric
import io.github.proify.lyricon.xposed.systemui.lyric.HandleGestureTracker
import io.github.proify.lyricon.xposed.systemui.lyric.LyricGestureActions
import io.github.proify.lyricon.xposed.systemui.lyric.LyricPrefs
import io.github.proify.lyricon.xposed.systemui.lyric.StatusBarGestureRegions
import io.github.proify.lyricon.xposed.logger.YLog
import io.github.proify.lyricon.xposed.systemui.lyric.StatusBarViewController
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.WeakHashMap
import kotlin.math.abs

/** Routes touches before status bar parents can intercept the lyric's own bounds. */
object StatusBarLyricTouchRouter {
    private var module: XposedModule? = null
    private val hookedMethods = mutableSetOf<Method>()
    private val roots = WeakHashMap<View, TouchState>()
    private val hitRect = Rect()
    private val rootRect = Rect()
    private val handler = Handler(Looper.getMainLooper())

    private class TouchState(controller: StatusBarViewController) {
        val controller = WeakReference(controller)
        private val slop = ViewConfiguration.get(controller.context).scaledTouchSlop.toFloat()
        val tracker = HandleGestureTracker(maxOf(slop, 12.dp.toFloat()), 28.dp.toFloat())
        var anchor: WeakReference<View>? = null
        var down: MotionEvent? = null
        var replaying = false
        var longPressed = false
        val regions = StatusBarGestureRegions(24.dp, 160.dp, 8.dp, 48.dp, 48.dp)
        val longPress = Runnable {
            if (tracker.longPress()) {
                longPressed = true
                dispatch(StatusBarLyric.GestureType.LONG_PRESS)
            }
        }

        fun dispatch(gesture: StatusBarLyric.GestureType) {
            val controller = controller.get() ?: return
            if (!controller.canRouteGestures()) {
                Log.i("LyriconGesture", "Status bar dispatch blocked: disabled/locked")
                return
            }
            val target = anchor?.get()?.takeIf { it.isShown }
                ?: controller.gestureTouchAnchor() ?: controller.statusBarView
            if (LyricPrefs.gestureHapticEnabled) {
                target.performHapticFeedback(if (gesture == StatusBarLyric.GestureType.LONG_PRESS)
                    HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.CLOCK_TICK)
            }
            Log.i("LyriconGesture", "Status bar recognized $gesture")
            LyricGestureActions.dispatch(gesture, target)
        }

        fun clear() {
            handler.removeCallbacks(longPress)
            tracker.cancel()
            anchor = null
            longPressed = false
            down?.recycle()
            down = null
        }
    }

    fun initialize(module: XposedModule) {
        this.module = module
    }

    fun register(controller: StatusBarViewController) {
        val module = module ?: return
        val root = controller.statusBarView.rootView
        if (!root.isAttachedToWindow) return
        roots[root]?.let { if (it.controller.get() === controller) return }
        var type: Class<*>? = root.javaClass
        var dispatch: Method? = null
        while (type != null && dispatch == null) {
            val currentType = type
            dispatch = runCatching {
                currentType.getDeclaredMethod("dispatchTouchEvent", MotionEvent::class.java)
            }.getOrNull()
            type = type.superclass
        }
        val method = dispatch ?: return
        runCatching {
            if (method !in hookedMethods) {
                // Keep a concrete hook class: R8 can break the framework's SAM bridge.
                module.hook(method).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val windowRoot = chain.thisObject as? View
                        val state = roots[windowRoot]
                        val event = chain.args[0] as MotionEvent
                        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                            Log.i("LyriconGesture", "Status bar input root=${System.identityHashCode(windowRoot)} " +
                                "registered=${state != null} raw=${event.rawX},${event.rawY} " +
                                "lag=${SystemClock.uptimeMillis() - event.eventTime}ms")
                        }
                        return if (windowRoot != null && state != null && !state.replaying &&
                            route(windowRoot, state, event)) true else chain.proceed()
                    }
                })
                hookedMethods.add(method)
                YLog.info("StatusBarLyricTouchRouter", "Registered ${method.declaringClass.name}")
                Log.i("LyriconGesture", "Registered root=${root.javaClass.name}, dispatch=${method.declaringClass.name}")
            }
            roots[root]?.clear()
            roots[root] = TouchState(controller)
            updateTouchBounds(controller)
            Log.i("LyriconGesture", "Status bar routing bound root=${System.identityHashCode(root)}")
        }.onFailure { YLog.error("StatusBarLyricTouchRouter", "Cannot route lyric touches", it) }
    }

    fun updateTouchBounds(controller: StatusBarViewController) {
        val root = controller.statusBarView.rootView
        val state = roots[root] ?: return
        refreshTouchBounds(root, state, controller)
    }

    private fun refreshTouchBounds(root: View, state: TouchState,
        controller: StatusBarViewController): Boolean {
        if (!root.getGlobalVisibleRect(rootRect)) return false
        state.regions.setWindowSize(rootRect.width(), rootRect.height())
        val lyrics = controller.lyricView
        if (lyrics.isShown && !lyrics.isDisabledVisible && lyrics.getGlobalVisibleRect(hitRect)) {
            state.regions.rememberLyrics(relativeBounds(hitRect, rootRect))
        }
        controller.gestureClockAnchor()?.let { clock ->
            if (clock.getGlobalVisibleRect(hitRect)) {
                state.regions.rememberClock(relativeBounds(hitRect, rootRect))
            }
        }
        return true
    }

    private fun relativeBounds(bounds: Rect, root: Rect) = StatusBarGestureRegions.Bounds(
        bounds.left - root.left, bounds.top - root.top,
        bounds.right - root.left, bounds.bottom - root.top)

    fun unregister(controller: StatusBarViewController) {
        val iterator = roots.entries.iterator()
        while (iterator.hasNext()) {
            val state = iterator.next().value
            if (state.controller.get() == null || state.controller.get() === controller) {
                state.clear()
                Log.i("LyriconGesture", "Status bar routing removed")
                iterator.remove()
            }
        }
    }

    private fun route(root: View, state: TouchState, event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            state.clear()
            val controller = state.controller.get() ?: run {
                Log.i("LyriconGesture", "Status bar rejected: controller unavailable")
                return false
            }
            if (!controller.canRouteGestures() || !root.isShown ||
                !refreshTouchBounds(root, state, controller)) {
                Log.i("LyriconGesture", "Status bar rejected: enabled=${controller.lyricView.gestureEnabled} " +
                    "locked=${controller.lyricView.isHideOnLockScreen()} rootShown=${root.isShown}")
                return false
            }
            val anchor = controller.gestureTouchAnchor()
            val x = event.rawX.toInt() - rootRect.left
            val y = event.rawY.toInt() - rootRect.top
            if (!state.regions.contains(x, y)) {
                Log.i("LyriconGesture", "Status bar missed: anchor=${anchor?.javaClass?.simpleName} " +
                    "root=$rootRect lyrics=${state.regions.lyrics} clock=${state.regions.clock}")
                return false
            }
            state.anchor = WeakReference(anchor ?: root)
            state.down = MotionEvent.obtain(event)
            state.tracker.down(event.rawX, event.rawY)
            handler.postAtTime(state.longPress, event.downTime + ViewConfiguration.getLongPressTimeout())
            Log.i("LyriconGesture", "Status bar DOWN anchor=${if (anchor === controller.lyricView) "lyrics" else "clock/recovery"}")
        }
        val down = state.down ?: return false
        val dx = event.rawX - down.rawX
        val dy = event.rawY - down.rawY
        val pullDownSlop = maxOf(ViewConfiguration.get(root.context).scaledTouchSlop * 2f, 16.dp.toFloat())
        val verticalDrag = event.actionMasked == MotionEvent.ACTION_MOVE &&
                abs(dy) > pullDownSlop && abs(dy) > abs(dx)
        if ((!state.longPressed && verticalDrag) || event.pointerCount > 1) {
            Log.i("LyriconGesture", "Status bar handed to system: dx=$dx dy=$dy " +
                "held=${event.eventTime - down.downTime}ms pointers=${event.pointerCount}")
            // Give notification pull-down its original DOWN before forwarding the current MOVE.
            handler.removeCallbacks(state.longPress)
            state.tracker.cancel()
            state.replaying = true
            try {
                root.dispatchTouchEvent(down)
            } finally {
                state.replaying = false
                state.clear()
            }
            return false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val wasPending = handler.hasCallbacks(state.longPress)
                if (!state.tracker.move(event.rawX, event.rawY)) {
                    if (wasPending) Log.i("LyriconGesture", "Status bar long press cancelled by movement: dx=$dx dy=$dy")
                    handler.removeCallbacks(state.longPress)
                }
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(state.longPress)
                val heldFor = event.eventTime - down.downTime
                Log.i("LyriconGesture", "Status bar UP held=${heldFor}ms, longPressed=${state.longPressed}")
                if (state.tracker.longPressAtUp(event.rawX, event.rawY, heldFor,
                        ViewConfiguration.getLongPressTimeout().toLong())) {
                    state.longPressed = true
                    state.dispatch(StatusBarLyric.GestureType.LONG_PRESS)
                }
                when (state.tracker.up(event.rawX, event.rawY)) {
                    HandleGestureTracker.Action.TAP -> state.dispatch(StatusBarLyric.GestureType.TAP)
                    HandleGestureTracker.Action.SWIPE_LEFT -> state.dispatch(StatusBarLyric.GestureType.SWIPE_LEFT)
                    HandleGestureTracker.Action.SWIPE_RIGHT -> state.dispatch(StatusBarLyric.GestureType.SWIPE_RIGHT)
                    null -> Unit
                }
                state.clear()
            }
            MotionEvent.ACTION_CANCEL -> {
                Log.i("LyriconGesture", "Status bar CANCEL held=${event.eventTime - down.downTime}ms")
                state.clear()
            }
        }
        return true
    }
}
