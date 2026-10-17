package io.github.proify.lyricon.common.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/** Disk access and decoding must be called from a worker thread. */
object CoverImages {
    fun key(file: File): String = "${file.absolutePath}:${file.length()}:${file.lastModified()}"

    fun decode(file: File, maxSize: Int = 256): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply { inSampleSize = 1 }
        while (maxOf(bounds.outWidth, bounds.outHeight) / options.inSampleSize > maxSize * 2) {
            options.inSampleSize *= 2
        }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
        val largest = maxOf(decoded.width, decoded.height)
        if (largest <= maxSize) return decoded
        val scale = maxSize.toFloat() / largest
        return try {
            Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1),
                (decoded.height * scale).toInt().coerceAtLeast(1), true)
        } finally {
            decoded.recycle()
        }
    }
}
