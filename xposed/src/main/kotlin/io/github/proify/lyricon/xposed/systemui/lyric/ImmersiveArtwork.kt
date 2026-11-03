package io.github.proify.lyricon.xposed.systemui.lyric

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.github.proify.lyricon.common.util.CoverImages
import io.github.proify.lyricon.xposed.systemui.util.NotificationCoverHelper
import java.io.ByteArrayOutputStream

internal object ImmersiveArtwork {
    data class Cover(val key: String, val bytes: ByteArray? = null)

    fun load(player: String, title: String, artist: String, knownKey: String = ""): Cover {
        val file = NotificationCoverHelper.getCachedCoverFile(player, title, artist)?.takeIf { it.isFile }
        if (file != null) {
            val key = CoverImages.key(file)
            if (key == knownKey) return Cover(key)
            CoverImages.decode(file, 128)?.let { return encode(key, it) }
        }
        val notification = ImmersiveNotificationCover.find(player, title) ?: return Cover("")
        if (notification.key == knownKey) return Cover(notification.key)
        val bitmap = BitmapFactory.decodeByteArray(notification.bytes, 0, notification.bytes.size) ?: return Cover("")
        return encode(notification.key, bitmap)
    }

    private fun encode(key: String, bitmap: Bitmap): Cover = try {
        val scale = minOf(1f, 128f / maxOf(bitmap.width, bitmap.height))
        val small = if (scale < 1) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1), true) else bitmap
        try { Cover(key, ByteArrayOutputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 85, it); it.toByteArray() }) }
        finally { if (small !== bitmap) small.recycle() }
    } finally { bitmap.recycle() }
}
