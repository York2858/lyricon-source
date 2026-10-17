/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.colorextractor.palette

import android.graphics.Bitmap
import android.graphics.Color
import android.util.LruCache
import io.github.proify.lyricon.common.util.CoverImages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

object ColorExtractor {
    private val scope = CoroutineScope(Dispatchers.IO.limitedParallelism(2) + SupervisorJob())
    private val cache = LruCache<String, ColorPaletteResult>(32)
    private val pending = mutableMapOf<String, Deferred<ColorPaletteResult?>>()
    private val lock = Any()

    /** Cache lookup precedes decoding; all views share one extraction per file version. */
    suspend fun extract(file: File?): ColorPaletteResult? {
        if (file == null) return null
        val key = withContext(Dispatchers.IO) { CoverImages.key(file) }
        val task = synchronized(lock) {
            cache.get(key)?.let { return it }
            pending.getOrPut(key) {
                scope.async {
                    var bitmap: Bitmap? = null
                    try {
                        bitmap = CoverImages.decode(file)
                        bitmap?.let(::extractPalette)?.also { cache.put(key, it) }
                    } catch (_: Exception) {
                        null
                    } finally {
                        bitmap?.recycle()
                    }
                }
            }
        }
        // Removal belongs to the shared task, even when the requesting view is cancelled.
        task.invokeOnCompletion { synchronized(lock) { if (pending[key] === task) pending.remove(key) } }
        return task.await()
    }

    /** Compatibility entry point: the caller retains ownership of its bitmap. */
    fun extractAsync(bitmap: Bitmap, cacheKey: (suspend () -> String)? = null,
                     callback: (ColorPaletteResult?) -> Unit) {
        scope.launch {
            val key = cacheKey?.invoke()
            val result = try {
                key?.let(cache::get) ?: extractPalette(bitmap).also { value ->
                    if (key != null) cache.put(key, value)
                }
            } catch (_: Exception) { null }
            withContext(Dispatchers.Main) { callback(result) }
        }
    }

    private fun extractPalette(bitmap: Bitmap): ColorPaletteResult {
        val palette = ColorExtractorImpl.extractThemePalette(bitmap)
        fun colors(dark: Boolean): ThemeColors {
            val values = if (dark) palette.onBlackBackground else palette.onWhiteBackground
            return ThemeColors(values.firstOrNull() ?: Color.BLACK, values.toIntArray())
        }
        return ColorPaletteResult(colors(false), colors(true))
    }
}
