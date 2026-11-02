package io.github.proify.lyricon.app.activity.lyric.immersive

import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.proify.lyricon.app.activity.BaseActivity
import io.github.proify.lyricon.app.activity.lyric.immersive.halcyon.SettingsManager
import io.github.proify.lyricon.app.compose.theme.AppTheme

class ImmersiveSettingsActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i("ImmersiveSettings", "open settings-v59")
        setContent {
            AppTheme {
                val manager = remember { SettingsManager.getInstance(this) }
                val values by manager.values.collectAsState()
                MaterialTheme(colorScheme = remember { darkColorScheme() }) {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                            TextButton(onClick = { finish() }, modifier = Modifier.padding(horizontal = 12.dp)) {
                                Text("返回歌词")
                            }
                            ImmersiveSettingsContent(manager, values)
                        }
                    }
                }
            }
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val handled = super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            Log.i("ImmersiveSettings", "touch-up x=${event.x.toInt()} y=${event.y.toInt()} handled=$handled")
        }
        return handled
    }
}
