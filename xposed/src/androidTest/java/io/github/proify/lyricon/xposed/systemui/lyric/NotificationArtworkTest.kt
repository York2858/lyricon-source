package io.github.proify.lyricon.xposed.systemui.lyric

import android.app.Notification
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Parcel
import android.os.Process
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class NotificationArtworkTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun bitmap(width: Int = 104, height: Int = 104): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            density = Bitmap.DENSITY_NONE
            eraseColor(Color.RED)
        }

    private fun notification(): Notification = Notification.Builder(context, "artwork-test")
        .setSmallIcon(android.R.drawable.ic_media_play)
        .setContentTitle("Rain When I Die")
        .setCategory(Notification.CATEGORY_TRANSPORT)
        .setStyle(Notification.MediaStyle())
        .build()

    private fun roundTrip(notification: Notification): Notification = Parcel.obtain().let { parcel ->
        try {
            notification.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            Notification.CREATOR.createFromParcel(parcel)
        } finally { parcel.recycle() }
    }

    private fun assertDecoded(notification: Notification, width: Int, height: Int) {
        val icon = requireNotNull(NotificationArtwork.icon(notification))
        val bytes = requireNotNull(NotificationArtwork.encode(context, icon))
        // This is the same byte-array decoding used by the immersive lyrics client.
        val decoded = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        try {
            assertEquals(width, decoded.width)
            assertEquals(height, decoded.height)
            val pixel = decoded.getPixel(width / 2, height / 2)
            assertTrue(Color.red(pixel) > 240)
            assertTrue(Color.green(pixel) < 15)
            assertTrue(Color.blue(pixel) < 15)
        } finally { decoded.recycle() }
    }

    @Test fun extrasOnlyIconSurvivesBinderParcelAndDecodesWithoutSessionArtwork() {
        val source = bitmap()
        try {
            val notification = notification().apply {
                extras.putParcelable(Notification.EXTRA_LARGE_ICON, Icon.createWithBitmap(source))
            }
            assertNull(notification.getLargeIcon())
            assertDecoded(roundTrip(notification), 104, 104)
            assertFalse(source.isRecycled)
        } finally { source.recycle() }
    }

    @Test fun extrasOnlyLegacyBitmapIsRead() {
        val source = bitmap()
        try {
            val notification = notification().apply {
                extras.putParcelable(Notification.EXTRA_LARGE_ICON, source)
            }
            assertNull(notification.getLargeIcon())
            assertDecoded(roundTrip(notification), 104, 104)
        } finally { source.recycle() }
    }

    @Test fun standardLargeIconStillWorks() {
        val source = bitmap()
        try {
            val notification = Notification.Builder(context, "artwork-test")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setLargeIcon(Icon.createWithBitmap(source)).build()
            assertDecoded(notification, 104, 104)
            assertFalse(source.isRecycled)
        } finally { source.recycle() }
    }

    @Test fun largeArtIsBoundedWithoutDistortingAspectRatio() {
        val source = bitmap(1024, 512)
        try {
            val notification = notification().apply {
                extras.putParcelable(Notification.EXTRA_LARGE_ICON, Icon.createWithBitmap(source))
            }
            assertDecoded(notification, 256, 128)
        } finally { source.recycle() }
    }

    @Suppress("DEPRECATION")
    @Test fun deprecatedLargeIconFieldIsRead() {
        val source = bitmap()
        try {
            val notification = notification().apply { largeIcon = source }
            assertNull(notification.getLargeIcon())
            assertDecoded(notification, 104, 104)
        } finally { source.recycle() }
    }

    @Test fun hardwareArtworkCanBeEncodedOnSoftwareCanvas() {
        val source = bitmap()
        val hardware = requireNotNull(source.copy(Bitmap.Config.HARDWARE, false))
        try {
            val notification = notification().apply {
                extras.putParcelable(Notification.EXTRA_LARGE_ICON, Icon.createWithBitmap(hardware))
            }
            assertDecoded(notification, 104, 104)
            assertFalse(hardware.isRecycled)
        } finally { hardware.recycle(); source.recycle() }
    }

    @Test fun smallAppIconIsNeverUsedAsAlbumArtwork() {
        assertNull(NotificationArtwork.icon(notification()))
    }

    @Test fun systemUiUnattachedListenerDoesNotSupplyTheArtworkContext() {
        val listener = object : NotificationListenerService() {}
        assertNull(listener.baseContext)
        val source = bitmap()
        try {
            val title = "notification-pipeline-${System.nanoTime()}"
            val notification = notification().apply {
                extras.putCharSequence(Notification.EXTRA_TITLE, title)
                extras.putParcelable(Notification.EXTRA_LARGE_ICON, Icon.createWithBitmap(source))
            }
            val item = StatusBarNotification("com.neutroncode.mp", "com.neutroncode.mp", 1, null,
                Process.myUid(), Process.myPid(), 0, notification, Process.myUserHandle(), System.currentTimeMillis())
            ImmersiveNotificationCover.attach(context)
            ImmersiveNotificationCover.onNotificationPosted(item)
            var cover: ImmersiveNotificationCover.Cover? = null
            val deadline = SystemClock.elapsedRealtime() + 5000
            while (cover == null && SystemClock.elapsedRealtime() < deadline) {
                cover = ImmersiveNotificationCover.find(item.packageName, title)
                if (cover == null) SystemClock.sleep(20)
            }
            val cached = requireNotNull(cover) { "Notification pipeline did not cache the artwork" }
            val decoded = requireNotNull(BitmapFactory.decodeByteArray(cached.bytes, 0, cached.bytes.size))
            try {
                assertEquals(104, decoded.width)
                assertEquals(104, decoded.height)
                assertEquals(title, cached.title)
            } finally { decoded.recycle() }
        } finally { source.recycle() }
    }
}
