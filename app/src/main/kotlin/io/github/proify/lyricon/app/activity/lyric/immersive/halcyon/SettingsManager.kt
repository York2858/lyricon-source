package io.github.proify.lyricon.app.activity.lyric.immersive.halcyon

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged

internal class SettingsManager private constructor(context: Context) {
    val preferences = context.applicationContext.getSharedPreferences("halcyon_lyrics", Context.MODE_PRIVATE)
    val values = MutableStateFlow(preferences.all.toMap())
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        values.value = preferences.all.toMap()
        if (key == null || key in io.github.proify.lyricon.app.bridge.ImmersiveAppearanceContract.KEYS)
            io.github.proify.lyricon.app.activity.lyric.immersive.ImmersiveAppearanceSync.publish(context, values.value)
    }
    init {
        preferences.registerOnSharedPreferenceChangeListener(listener)
        if (context.applicationContext is io.github.proify.lyricon.app.LyriconApp) {
            io.github.proify.lyricon.app.LyriconApp.addXposedServiceStateListener(object : io.github.proify.lyricon.app.LyriconApp.XposedServiceStateListener {
                override fun onServiceStateChanged(service: io.github.libxposed.service.XposedService?) {
                    if (service != null) io.github.proify.lyricon.app.activity.lyric.immersive.ImmersiveAppearanceSync.publish(context, preferences.all.toMap())
                }
            })
        }
    }
    fun boolean(key: String, default: Boolean) = values.map { it[key] as? Boolean ?: default }.distinctUntilChanged()
    fun integer(key: String, default: Int) = values.map { (it[key] as? Number)?.toInt() ?: default }.distinctUntilChanged()
    fun set(key: String, value: Boolean) {
        preferences.edit().putBoolean(key, value).apply()
        values.value = preferences.all.toMap()
        android.util.Log.i("ImmersiveSettings", "apply key=$key requested=$value readBack=${preferences.getBoolean(key, !value)}")
    }
    fun set(key: String, value: Int) {
        preferences.edit().putInt(key, value).apply()
        values.value = preferences.all.toMap()
        android.util.Log.i("ImmersiveSettings", "apply key=$key requested=$value readBack=${preferences.getInt(key, value)}")
    }
    fun set(key: String, value: String) {
        preferences.edit().putString(key, value).apply()
        values.value = preferences.all.toMap()
        android.util.Log.i("ImmersiveSettings", "apply string key=$key length=${value.length}")
    }
    val lyricSustainMotion = boolean("sustain_motion", true)
    val lyricPronunciationBelow = boolean("pronunciation_below", false)
    val appleMusicLyricsSustainThresholdMs = integer("sustain_threshold", 1200)
    val lyricNonCurrentBlurPercent = integer("non_current_blur", 70)
    val lyricWordSeekEnabled = boolean("word_seek", false)
    val lyricTouchFeedbackEnabled = boolean("touch_feedback", false)
    val lyricPauseCurrentOnly = boolean("pause_current_only", true)
    val lyricRainbowEnabled = boolean("rainbow", false)
    val lyricHdrHighlightEnabled = boolean("hdr", false)
    val playerAppleFlowSpeed = integer("apple_flow_speed", 10)
    val playerBeautifulLyricsSpeed = integer("beautiful_speed", 25)
    val playerBeautifulLyricsBlur = integer("beautiful_blur", 32)
    val playerBeautifulLyricsBrightness = integer("beautiful_brightness", 70)

    companion object {
        const val DEFAULT_APPLE_MUSIC_LYRICS_SUSTAIN_THRESHOLD_MS = 1200
        const val DEFAULT_PLAYER_APPLE_FLOW_SPEED = 10
        const val PLAYER_LYRIC_ALIGN_CENTER = 1
        const val PLAYER_LYRIC_ALIGN_RIGHT = 2
        private var instance: SettingsManager? = null
        fun getInstance(context: Context): SettingsManager = instance ?: synchronized(this) {
            instance ?: SettingsManager(context).also { instance = it }
        }
    }
}
