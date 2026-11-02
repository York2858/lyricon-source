package io.github.proify.lyricon.xposed.systemui.lyric

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import io.github.proify.lyricon.xposed.logger.YLog

/** Explicitly open Lyrico's permission bridge from the user-triggered SystemUI gesture. */
internal object LyricoRecognitionLauncher {
    fun launch(context: Context) {
        for (packageName in listOf("com.lonx.lyrico", "com.lonx.lyrico.debug")) {
            try {
                context.startActivity(Intent()
                    .setClassName(packageName, "com.lonx.lyrico.recognition.FloatingRecognitionShortcut")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS))
                return
            } catch (_: ActivityNotFoundException) {
                // Try the debug installation when release is absent or predates the shortcut.
            } catch (error: SecurityException) {
                YLog.error("LyricoRecognitionLauncher", "Recognition shortcut unavailable", error)
            }
        }
        Toast.makeText(context, "请先安装或更新 Lyrico，以使用悬浮识曲", Toast.LENGTH_LONG).show()
    }
}
