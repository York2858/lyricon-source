package io.github.proify.lyricon.app.activity.lyric.immersive

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow

/** Immediate local persistence; the translation database is synchronized separately. */
internal class ImmersiveOffsetStore(private val preferences: SharedPreferences) {
    val values = MutableStateFlow(preferences.all.toMap())

    fun offset(key: String, fallback: Int = 0): Int =
        if (key.isBlank()) 0 else preferences.getInt(key, fallback).coerceIn(-5000, 5000)

    fun set(key: String, offset: Int) {
        if (key.isBlank()) return
        preferences.edit().putInt(key, offset.coerceIn(-5000, 5000)).putBoolean("pending:$key", true).apply()
        values.value = preferences.all.toMap()
    }

    fun pending(key: String) = key.isNotBlank() && preferences.getBoolean("pending:$key", false)

    fun markSynced(key: String, expectedOffset: Int) {
        if (offset(key) != expectedOffset) return
        preferences.edit().remove("pending:$key").apply()
        values.value = preferences.all.toMap()
    }

    companion object {
        @Volatile private var instance: ImmersiveOffsetStore? = null
        fun get(context: Context): ImmersiveOffsetStore = instance ?: synchronized(this) {
            instance ?: ImmersiveOffsetStore(context.applicationContext.getSharedPreferences(
                "immersive_song_offsets", Context.MODE_PRIVATE)).also { instance = it }
        }
    }
}
