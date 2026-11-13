package io.github.proify.lyricon.lyric.ai.translate

import java.security.MessageDigest
import java.util.Locale

/** Match the original lyric version across AI providers; never include translated text. */
fun translationTimelineKey(title: String, artist: String, originals: List<String>, timing: List<TranslationTiming>): String {
    val payload = listOf(title.trim().lowercase(Locale.ROOT), artist.trim().lowercase(Locale.ROOT),
        TranslationLibraryContract.json.encodeToString(originals.map(String::trim)),
        TranslationLibraryContract.json.encodeToString(timing.sortedBy { it.index }.map { "${it.index}:${it.begin}:${it.end}" }))
    val bytes = TranslationLibraryContract.json.encodeToString(payload).toByteArray()
    return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

fun TranslationTiming.withLyricOffset(offset: Int): TranslationTiming {
    if (offset == 0) return this
    fun shift(value: Long) = (value + offset).coerceAtLeast(0)
    val begin = shift(begin)
    val end = shift(end).coerceAtLeast(begin + 1)
    return copy(begin = begin, end = end, words = words.map {
        val wordBegin = shift(it.begin).coerceIn(begin, end)
        it.copy(begin = wordBegin, end = shift(it.end).coerceIn(wordBegin, end))
    })
}
