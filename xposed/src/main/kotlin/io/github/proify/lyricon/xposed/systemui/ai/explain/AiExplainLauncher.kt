/* Copyright 2026 Proify, Tomakino. Licensed under the Apache License, Version 2.0. */
package io.github.proify.lyricon.xposed.systemui.ai.explain

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.view.View
import android.widget.Toast
import io.github.proify.lyricon.common.util.CoverImages
import io.github.proify.lyricon.lyric.ai.explain.AiExplainContract as Contract
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.xposed.BuildConfig
import io.github.proify.lyricon.xposed.logger.YLog
import io.github.proify.lyricon.xposed.systemui.lyric.LyricViewController
import io.github.proify.lyricon.xposed.systemui.util.MediaTrackMeta
import io.github.proify.lyricon.xposed.systemui.util.NotificationCoverHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Frozen lyric snapshot with a narrowly scoped capability to request a fresh snapshot. */
object AiExplainLauncher {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // No exported receiver, polling task, Activity reference, or long-lived client listener.
    private val requester by lazy {
        Messenger(Handler(Looper.getMainLooper()) { message ->
            if (message.what != Contract.REQUEST_SNAPSHOT) return@Handler false
            val reply = message.replyTo ?: return@Handler true
            val snapshot = snapshot(LyricViewController.currentSong)
            runCatching {
                reply.send(Message.obtain(null, Contract.RESULT_SNAPSHOT).apply { data = snapshot })
            }
            true
        })
    }

    @Suppress("UNUSED_PARAMETER")
    fun launch(anchor: View, button: View, song: Song?) {
        val context = button.context
        val snapshot = snapshot(song)
        if (snapshot.getStringArrayList(Contract.EXTRA_LINE_TEXTS).isNullOrEmpty()) {
            Toast.makeText(context, "暂未获取到歌词", Toast.LENGTH_SHORT).show()
            return
        }
        val player = LyricViewController.activePackage
        scope.launch {
            val cover = withContext(Dispatchers.IO) {
                runCatching {
                    val file = NotificationCoverHelper.getLatestCoverFile(player) ?: return@runCatching null
                    val bitmap = CoverImages.decode(file, 128) ?: return@runCatching null
                    try {
                        ByteArrayOutputStream().use {
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, it)
                            it.toByteArray()
                        }
                    } finally { bitmap.recycle() }
                }.getOrNull()
            }
            val intent = Intent().apply {
                setClassName(BuildConfig.APP_PACKAGE_NAME, Contract.ACTIVITY_CLASS)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                putExtras(snapshot)
                putExtra(Contract.EXTRA_COVER, cover)
                putExtra(Contract.EXTRA_SNAPSHOT_REQUESTER, requester)
            }
            runCatching { context.startActivity(intent) }
                .onFailure { YLog.error("AiExplainLauncher", "Failed to launch explanation", it) }
        }
    }

    private fun snapshot(song: Song?): Bundle {
        val meta = MediaTrackMeta.resolve(LyricViewController.activePackage)
        val lines = song?.lyrics.orEmpty().filter { !it.text.isNullOrBlank() }.sortedBy { it.begin }
        return Bundle().apply {
            putString(Contract.EXTRA_TITLE, meta?.title ?: song?.name.orEmpty())
            putString(Contract.EXTRA_ARTIST, meta?.artist ?: song?.artist.orEmpty())
            putString(Contract.EXTRA_ALBUM, meta?.album.orEmpty())
            putString(Contract.EXTRA_LYRICS, lines.joinToString("\n") { it.text.orEmpty() })
            putStringArrayList(Contract.EXTRA_LINE_TEXTS, ArrayList(lines.map { it.text.orEmpty() }))
            putLongArray(Contract.EXTRA_LINE_STARTS, lines.map { it.begin }.toLongArray())
            putLongArray(Contract.EXTRA_LINE_ENDS, lines.map { it.end }.toLongArray())
            putLong(Contract.EXTRA_POSITION, LyricViewController.currentLogicPosition)
        }
    }
}
