package io.github.proify.lyricon.app.activity.lyric.immersive.halcyon

import android.graphics.ImageDecoder
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun PlayerCoverImage(model: Uri, contentDescription: String?, modifier: Modifier, contentScale: ContentScale, sizePx: Int) {
    val context = LocalContext.current
    val bitmap by produceState<android.graphics.Bitmap?>(null, model) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, model)) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val ratio = sizePx.toFloat() / maxOf(info.size.width, info.size.height)
                    if (ratio < 1f) decoder.setTargetSize((info.size.width * ratio).toInt().coerceAtLeast(1), (info.size.height * ratio).toInt().coerceAtLeast(1))
                }
            }.getOrNull()
        }
    }
    bitmap?.let { Image(it.asImageBitmap(), contentDescription, modifier, contentScale = contentScale) }
}
