package io.github.proify.lyricon.xposed.systemui.lyric.control

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.github.proify.lyricon.xposed.systemui.util.MediaTrackMeta
import org.junit.Assert.*
import org.junit.Test

class MediaPanelTestActivity : Activity()

class MediaPanelSurfaceTest {
    @Test fun headerProgressAndAllBackgroundModesWorkInsideAPlainAndroidWindow() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val pkg = inst.targetContext.packageName
        ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(
            "am start -W -n $pkg/${MediaPanelTestActivity::class.java.name}")).use { it.readBytes() }
        lateinit var activity: MediaPanelTestActivity
        lateinit var panel: LyricControlPanel
        val cover = Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(35,90,190)) }
        val custom = Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(24,190,90)) }
        fun bounds(view: View) = Rect().also { view.getGlobalVisibleRect(it) }
        fun nodes(view: View): List<View> = listOf(view) + if(view is ViewGroup) (0 until view.childCount).flatMap { nodes(view.getChildAt(it)) } else emptyList()
        inst.runOnMainSync {
            activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MediaPanelTestActivity>().single()
            panel = LyricControlPanel(activity)
            panel.bindSong(null, 10000, MediaTrackMeta.TrackMeta("Blues", "Fixture artist", "Album", 200000))
            panel.setCover(cover)
            panel.setAppearance(mapOf("background_mode" to 3))
            panel.measure(View.MeasureSpec.makeMeasureSpec(activity.resources.displayMetrics.widthPixels-32,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(activity.resources.displayMetrics.heightPixels,View.MeasureSpec.AT_MOST))
            activity.setContentView(FrameLayout(activity).apply {
                addView(panel, FrameLayout.LayoutParams(-1,-2).apply { setMargins(16,40,16,0) })
            })
        }
        try {
            inst.waitForIdleSync(); Thread.sleep(500)
            inst.runOnMainSync {
                fun node(name:String) = nodes(panel).single { it.contentDescription?.toString()==name }
                val art = bounds(node("专辑封面")); val title = bounds(node("歌名")); val artist = bounds(node("歌手")); val seek = bounds(node("播放进度"))
                assertTrue(title.left >= art.right)
                assertTrue(artist.left >= art.right)
                assertTrue(title.top >= art.top && artist.bottom <= art.bottom)
                assertTrue("progress belongs below the whole header", seek.top >= art.bottom)
                assertEquals(art.left, seek.left)
                assertTrue(seek.right > artist.right - 5)
                assertTrue("background must not expand the card to screen height", panel.height < activity.resources.displayMetrics.density * 300)
                assertTrue((node("专辑封面") as ImageView).drawable.let { it as BitmapDrawable }.bitmap.sameAs(cover))
            }
            for(mode in listOf(0,1,2,3,4)) {
                inst.runOnMainSync { panel.setAppearance(mapOf("background_mode" to mode,"custom_opacity" to 100,"custom_dim" to 0,"flow_enabled" to true), custom); panel.setPlaying(true) }
                inst.waitForIdleSync(); Thread.sleep(220)
            }
            val image = inst.uiAutomation.takeScreenshot()
            var area = Rect()
            inst.runOnMainSync { area = bounds(panel) }
            val crop = Bitmap.createBitmap(image, area.left,area.top,area.width(),area.height())
            val pixel = crop.getPixel(crop.width/2,12)
            assertTrue("custom image reaches the media card", Color.green(pixel)>Color.red(pixel)*2)
            java.io.File(inst.targetContext.getExternalFilesDir(null),"media-panel-custom.png").outputStream().use { crop.compress(Bitmap.CompressFormat.PNG,100,it) }
            inst.runOnMainSync { panel.setAppearance(mapOf("background_mode" to 3)); panel.setPlaying(false) }
            inst.waitForIdleSync(); Thread.sleep(150)
            val gradient = inst.uiAutomation.takeScreenshot()
            Bitmap.createBitmap(gradient,area.left,area.top,area.width(),area.height()).let { bitmap ->
                java.io.File(inst.targetContext.getExternalFilesDir(null),"media-panel-gradient.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            }
            inst.runOnMainSync { panel.setCover(null) }
            inst.runOnMainSync { assertNull((nodes(panel).single { it.contentDescription?.toString()=="专辑封面" } as ImageView).drawable) }
        } finally { inst.runOnMainSync { activity.finish() }; inst.waitForIdleSync() }
    }
}
