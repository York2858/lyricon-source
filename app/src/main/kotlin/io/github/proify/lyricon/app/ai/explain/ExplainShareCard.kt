package io.github.proify.lyricon.app.ai.explain

import android.content.ContentValues
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.Base64
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class ExplainCardColors(val background: Int, val foreground: Int, val secondary: Int, val accent: Int)

object ExplainShareCard {
    /** Bound user-selected text to keep both preview and exported image within memory limits. */
    fun render(song: ExplainSong, quote: String, excerpt: String, colors: ExplainCardColors): Bitmap {
        val width = 1080
        val margin = 80f
        val textWidth = width - margin.toInt() * 2
        fun block(text: String, size: Float, color: Int, maxLines: Int, bold: Boolean = false): StaticLayout {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                this.textSize = size
                this.color = color
                typeface = if (bold) Typeface.create("sans-serif", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL)
            }
            return StaticLayout.Builder.obtain(text, 0, text.length, paint, textWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false)
                .setLineSpacing(size * .45f, 1f).setMaxLines(maxLines)
                .setEllipsize(TextUtils.TruncateAt.END).build()
        }
        val quoteLayout = block(quote.take(600), 54f, colors.foreground, 8, true)
        val excerptLayout = block(plainText(excerpt).take(1800), 38f, colors.foreground, 30)
        val title = block(song.title.ifBlank { "未知歌曲" }.take(120), 42f, colors.foreground, 2, true)
        val metadata = block(listOf(song.artist, song.album).filter { it.isNotBlank() }.joinToString(" · ").take(200),
            30f, colors.secondary, 3)
        val footer = block("Lyricon · AI 音乐解读，仅供参考", 28f, colors.secondary, 1)
        val height = (margin * 2 + 160 + quoteLayout.height + 48 + excerptLayout.height + 90 +
            title.height + 18 + metadata.height + 80 + footer.height).toInt()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(colors.background)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.accent }
        canvas.drawRoundRect(RectF(margin, margin, margin + 104, margin + 104), 24f, 24f, paint)
        val cover = song.coverBase64?.let { encoded -> runCatching {
            val bytes = Base64.decode(encoded, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull() }
        if (cover != null) {
            canvas.drawBitmap(cover, null, RectF(margin, margin, margin + 104, margin + 104), paint)
            cover.recycle()
        } else {
            paint.color = colors.background
            paint.textSize = 66f
            canvas.drawText("♪", margin + 27, margin + 76, paint)
        }
        var y = margin + 160
        fun draw(layout: StaticLayout, gap: Float) {
            canvas.save()
            canvas.translate(margin, y)
            layout.draw(canvas)
            canvas.restore()
            y += layout.height + gap
        }
        draw(quoteLayout, 48f)
        draw(excerptLayout, 90f)
        draw(title, 18f)
        draw(metadata, 80f)
        draw(footer, 0f)
        return bitmap
    }

    suspend fun save(context: Context, bitmap: Bitmap): Uri = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "Lyricon-${System.currentTimeMillis()}.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Lyricon")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            })) { "无法创建图片" }
        try {
            checkNotNull(resolver.openOutputStream(uri)).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            uri
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    suspend fun share(context: Context, bitmap: Bitmap) {
        val intent = prepareShare(context, bitmap)
        withContext(Dispatchers.Main) {
            context.startActivity(Intent.createChooser(intent, "分享歌词解读").apply {
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }

    suspend fun prepareShare(context: Context, bitmap: Bitmap): Intent {
        val uri = withContext(Dispatchers.IO) {
            val directory = File(context.cacheDir, "ai_explain_cards").apply { mkdirs() }
            directory.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 7L * 86400000 }
                ?.forEach { it.delete() }
            val file = File(directory, "${UUID.randomUUID()}.png")
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            FileProvider.getUriForFile(context, "${context.packageName}.explain.files", file)
        }
        return Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = android.content.ClipData.newRawUri("歌词解读", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun plainText(value: String): String = value.replace(Regex("(?m)^\\s{0,3}(#{1,6}\\s+|>\\s?)"), "")
        .replace("**", "").replace("__", "").replace("`", "")
}
