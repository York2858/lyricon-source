package io.github.proify.lyricon.xposed.systemui.lyric

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class RemotePreferenceRefreshTest {
    private class Remote(var enabled: Boolean = false) {
        val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()
        val prefs = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { proxy, method, args ->
            when (method.name) {
                "registerOnSharedPreferenceChangeListener" -> {
                    listeners.add(args!![0] as SharedPreferences.OnSharedPreferenceChangeListener)
                    null
                }
                "unregisterOnSharedPreferenceChangeListener" -> {
                    listeners.remove(args!![0] as SharedPreferences.OnSharedPreferenceChangeListener)
                    null
                }
                "getBoolean" -> enabled
                "getAll" -> mapOf("enabled" to enabled)
                "equals" -> proxy === args!![0]
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "Test remote preferences"
                else -> error("Unexpected method ${method.name}")
            }
        } as SharedPreferences

        fun changed() = listeners.toList().forEach { it.onSharedPreferenceChanged(prefs, "enabled") }
    }

    @Test fun repeatedModeRefreshesLeaveOnlyOneLiveListener() {
        val remotes = List(31) { Remote() }
        var callbacks = 0
        val wrapper = LyricPrefs.StateSharedPreferencesWrapper(remotes.first().prefs) { _, _ -> callbacks++ }
        for (i in 1 until remotes.size) {
            wrapper.replacePreferences(remotes[i].prefs)
            assertEquals(0, remotes[i - 1].listeners.size)
            assertEquals(1, remotes[i].listeners.size)
        }
        remotes.forEach { it.changed() }
        assertEquals(1, callbacks)
        assertTrue(wrapper.hasChanged())
        assertTrue(wrapper.reload())
        assertFalse(wrapper.hasChanged())
    }

    @Test fun refreshingTheSameRemoteDoesNotDuplicateNotifications() {
        val remote = Remote()
        var callbacks = 0
        val wrapper = LyricPrefs.StateSharedPreferencesWrapper(remote.prefs) { _, _ -> callbacks++ }
        repeat(30) { wrapper.replacePreferences(remote.prefs) }
        assertEquals(1, remote.listeners.size)
        remote.changed()
        assertEquals(1, callbacks)
    }

    @Test fun rebindReadsTheNewRemoteAndRetainsChangeTracking() {
        val first = Remote(false)
        val second = Remote(true)
        val wrapper = LyricPrefs.StateSharedPreferencesWrapper(first.prefs) { _, _ -> }
        assertFalse(wrapper.getBoolean("enabled", false))
        wrapper.replacePreferences(second.prefs)
        assertTrue(wrapper.getBoolean("enabled", false))
        assertTrue(wrapper.reload())
        second.enabled = false
        second.changed()
        assertTrue(wrapper.hasChanged())
        assertFalse(wrapper.getBoolean("enabled", true))
    }
}
