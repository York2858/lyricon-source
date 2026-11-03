package io.github.proify.lyricon.xposed.systemui.lyric.control

import android.view.View
import android.view.ViewGroup
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class ControlFavoriteButtonTest {
    @Test fun oldEditorSlotNowTogglesFavoriteAndKeepsLyricsEntry() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val panel = LyricControlPanel(instrumentation.targetContext)
            var favorites = 0
            var lyrics = 0
            panel.actionListener = object : LyricControlPanel.ActionListener {
                override fun onPrevious() {}
                override fun onTogglePlay() {}
                override fun onNext() {}
                override fun onSeekTo(position: Long) {}
                override fun onFavorite(button: View) { favorites++ }
                override fun onImmersiveLyrics(button: View) { lyrics++ }
            }
            fun nodes(view: View): List<View> = listOf(view) + if (view is ViewGroup)
                (0 until view.childCount).flatMap { nodes(view.getChildAt(it)) } else emptyList()
            fun button(label: String) = nodes(panel).single { it.contentDescription?.toString() == label }
            panel.setFavorite(false)
            button("喜爱").performClick()
            assertEquals(1, favorites)
            panel.setFavorite(true)
            button("取消喜爱").performClick()
            assertEquals(2, favorites)
            button("沉浸歌词").performClick()
            assertEquals(1, lyrics)
            panel.setFavorite(true, busy = true)
            assertFalse(button("取消喜爱").isEnabled)
            assertFalse(nodes(panel).any { it.contentDescription?.toString()?.contains("音乐标签") == true })
        }
    }
}
