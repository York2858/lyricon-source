package io.github.proify.lyricon.xposed.systemui.lyric

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import io.github.proify.lyricon.lyric.ai.translate.isUsableLyricTranslation
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.subscriber.ProviderInfo
import java.util.Locale

internal object NeutronLyricWriteback {
    private const val PLAYER = "com.neutroncode.mp"
    private const val PROVIDER = "io.github.proify.lyricon.localprovider"
    private const val SOURCE_KEY = "$PROVIDER.neutron_lyric_source"
    private const val ACTION = "$PROVIDER.NEUTRON_LYRIC_WRITEBACK"
    private const val MAX_BYTES = 128 * 1024
    private var context: Context? = null
    private var lastSent: Update? = null

    fun initialize(context: Context) {
        this.context = context.applicationContext
    }

    data class Update(val source: String, val translation: String?)

    fun prepare(provider: ProviderInfo?, song: Song, publishOriginal: Boolean = false): Update? {
        if (provider?.providerPackageName != PROVIDER || provider.playerPackageName != PLAYER) return null
        val source = song.metadata?.get(SOURCE_KEY)?.takeIf { it.isNotBlank() } ?: return null
        val text = if (publishOriginal) null else encodeTranslations(song) ?: return null
        return Update(source, text)
    }

    fun send(update: Update) {
        val app = context ?: return
        if (lastSent == update || (update.translation == null && lastSent?.source == update.source)) return
        runCatching {
            app.sendBroadcast(Intent(ACTION).setPackage(PLAYER)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                .putExtra("revision", SystemClock.elapsedRealtimeNanos())
                .putExtra("source", update.source).putExtra("translation", update.translation))
            lastSent = update
        }.onFailure { Log.w("NeutronLyricWriteback", "Cannot deliver timed translations", it) }
    }

    internal fun encodeTranslations(song: Song): String? {
        val result = StringBuilder()
        for (line in song.lyrics.orEmpty().sortedBy { it.begin }) {
            val translation = line.translation?.replace('\n', ' ')?.replace('\r', ' ')?.trim()
            if (line.begin < 0 || translation.isNullOrEmpty() || !isUsableLyricTranslation(line.text, translation)) continue
            if (result.isNotEmpty()) result.append('\n')
            val time = line.begin
            result.append(String.format(Locale.ROOT, "[%02d:%02d.%03d]", time / 60_000, time / 1_000 % 60, time % 1_000))
            result.append(translation)
            if (result.length > MAX_BYTES) return null
        }
        return result.toString().takeIf { it.isNotEmpty() && it.toByteArray().size <= MAX_BYTES }
    }
}
