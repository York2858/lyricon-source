package io.github.proify.lyricon.xposed.systemui.lyric

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.os.Parcelable
import java.io.ByteArrayOutputStream

/** Some players publish artwork only in extras, without setting Notification.mLargeIcon. */
internal object NotificationArtwork {
    @Suppress("DEPRECATION")
    fun icon(notification: Notification): Icon? {
        notification.getLargeIcon()?.let { return it }
        return when (val extra = notification.extras.getParcelable<Parcelable>(Notification.EXTRA_LARGE_ICON)) {
            is Icon -> extra
            is Bitmap -> Icon.createWithBitmap(extra)
            else -> notification.largeIcon?.let(Icon::createWithBitmap)
        }
    }

    fun encode(context: Context, icon: Icon): ByteArray? {
        val original = icon.loadDrawable(context) ?: return null
        // A software Canvas cannot draw a hardware bitmap received through a notification.
        val softwareCopy = (original as? BitmapDrawable)?.bitmap
            ?.takeIf { it.config == Bitmap.Config.HARDWARE }
            ?.copy(Bitmap.Config.ARGB_8888, false)
        val drawable = softwareCopy?.let { BitmapDrawable(context.resources, it) } ?: original
        try {
            val sourceWidth = drawable.intrinsicWidth.takeIf { it > 0 } ?: 128
            val sourceHeight = drawable.intrinsicHeight.takeIf { it > 0 } ?: 128
            val scale = minOf(1f, 256f / maxOf(sourceWidth, sourceHeight))
            val width = (sourceWidth * scale).toInt().coerceAtLeast(1)
            val height = (sourceHeight * scale).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            return try {
                drawable.setBounds(0, 0, width, height)
                drawable.draw(Canvas(bitmap))
                ByteArrayOutputStream().use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output))
                    output.toByteArray()
                }
            } finally {
                bitmap.recycle()
            }
        } finally {
            softwareCopy?.recycle()
        }
    }
}
