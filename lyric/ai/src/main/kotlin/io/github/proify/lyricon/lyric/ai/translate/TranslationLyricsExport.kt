package io.github.proify.lyricon.lyric.ai.translate

import java.util.Locale

enum class TranslationExportFormat(val label: String, val extension: String, val mimeType: String, val description: String) {
    TXT("纯文本 TXT", "txt", "text/plain", "不带时间轴，适合阅读与保存"),
    LINE("逐行 LRC", "lrc", "application/x-lrc", "保留每句的开始、结束时间"),
    WORD("逐字 LRC", "lrc", "application/x-lrc", "使用 [时间] 标记原文逐字时间"),
    ENHANCED("增强逐字 LRC", "lrc", "application/x-lrc", "使用 <时间> 标记原文逐字时间"),
    TTML("TTML", "ttml", "application/ttml+xml", "保留原文逐字时间，译文与原句同步"),
}

enum class TranslationExportContent(val label: String) {
    BILINGUAL("双语"), ORIGINAL("仅原文"), TRANSLATION("仅译文")
}

data class TranslationExportOptions(val format: TranslationExportFormat = TranslationExportFormat.TXT,
    val content: TranslationExportContent = TranslationExportContent.BILINGUAL)

fun translationExportFileName(version: TranslationVersion, options: TranslationExportOptions,
    audioFileName: String? = null): String {
    // Lyrico uses the actual audio basename when available, otherwise its default metadata name.
    val audioBaseName = audioFileName?.substringAfterLast('/')?.substringAfterLast('\\')
        ?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
    // A real audio filename is already valid on its storage provider. Keep it verbatim,
    // including punctuation, spaces and long names, so players can find its sidecar lyrics.
    if (audioBaseName != null) return "$audioBaseName.${options.format.extension}"
    val rawName = listOf(version.artist.trim(), version.title.trim()).filter { it.isNotBlank() }
        .joinToString(" - ")
    val name = rawName
        .map { if (it in "\\/:*?\"<>|" || it.isISOControl()) '_' else it }.joinToString("")
        .trim().take(120).trimEnd('.', ' ').ifBlank { "lyrics" }
    return "$name.${options.format.extension}"
}

/** Timestamps are captured from the player. No timing is invented for AI-generated translations. */
fun exportTranslationLyrics(version: TranslationVersion, lines: List<TranslationLine>,
    options: TranslationExportOptions): String {
    require(lines.isNotEmpty()) { "没有可以导出的歌词" }
    val original = options.content != TranslationExportContent.TRANSLATION
    val translated = options.content != TranslationExportContent.ORIGINAL
    val cleanLines = lines.map { line ->
        line.copy(translation = line.translation.takeIf { isUsableLyricTranslation(line.original, it) }.orEmpty())
    }.filter { original || it.translation.isNotBlank() }
    require(cleanLines.isNotEmpty()) { "没有有效译文可以导出，请重新播放歌曲获取翻译" }
    require(!original || cleanLines.all { it.original.isNotBlank() }) { "记录缺少原文，请再次播放歌曲补齐后导出，或选择仅译文" }
    if (options.format == TranslationExportFormat.TXT) {
        return translationText(version, cleanLines.map { it.copy(original = if (original) it.original else "",
            translation = if (translated) it.translation else "") })
    }
    require(cleanLines.all { it.begin != null && it.end != null && it.begin >= 0 && it.end > it.begin }) {
        "记录缺少完整时间轴，请再次播放这首歌补齐后导出"
    }
    val ordered = cleanLines.sortedWith(compareBy<TranslationLine> { it.begin }.thenBy { it.index })
    return if (options.format == TranslationExportFormat.TTML) ttml(version, ordered, original, translated)
        else lrc(version, ordered, options, original, translated)
}

private fun stamp(time: Long): String = String.format(Locale.ROOT, "%02d:%02d.%03d", time / 60_000, time / 1000 % 60, time % 1000)
private fun clock(time: Long): String = String.format(Locale.ROOT, "%02d:%02d:%02d.%03d", time / 3_600_000, time / 60_000 % 60, time / 1000 % 60, time % 1000)
private fun plain(text: String) = text.replace('\r', ' ').replace('\n', ' ')
private fun tag(text: String) = plain(text).replace('[', '［').replace(']', '］')

private fun validWords(line: TranslationLine): List<TranslationWord> = line.words.takeIf { words ->
    words.size > 1 && words.joinToString("") { it.text }.trim() == line.original.trim() &&
        words.all { it.begin >= line.begin!! && it.end > it.begin && it.end <= line.end!! } &&
        words.zipWithNext().all { (a, b) -> a.begin <= b.begin }
}.orEmpty()

private fun lrc(version: TranslationVersion, lines: List<TranslationLine>, options: TranslationExportOptions,
    original: Boolean, translated: Boolean) = buildString {
    if (version.title.isNotBlank()) appendLine("[ti:${tag(version.title)}]")
    if (version.artist.isNotBlank()) appendLine("[ar:${tag(version.artist)}]")
    lines.forEach { line ->
        val start = stamp(line.begin!!); val end = stamp(line.end!!)
        if (original) {
            val words = validWords(line)
            if (options.format == TranslationExportFormat.LINE || words.isEmpty()) appendLine("[$start]${plain(line.original)}[$end]")
            else {
                if (options.format == TranslationExportFormat.ENHANCED) append("[$start]")
                words.forEach { word ->
                    if (options.format == TranslationExportFormat.ENHANCED) append("<${stamp(word.begin)}>")
                    else append("[${stamp(word.begin)}]")
                    append(plain(word.text))
                }
                val wordEnd = stamp(words.last().end)
                if (options.format == TranslationExportFormat.ENHANCED) appendLine("<$wordEnd>") else appendLine("[$wordEnd]")
            }
        }
        // The translator supplies sentence text, so it shares the real sentence timestamp.
        if (translated && line.translation.isNotBlank()) appendLine("[$start]${plain(line.translation)}[$end]")
    }
}.trimEnd()

private fun xml(value: String) = buildString {
    value.codePoints().forEach { cp ->
        when (cp) {
            38 -> append("&amp;"); 60 -> append("&lt;"); 62 -> append("&gt;")
            34 -> append("&quot;"); 39 -> append("&apos;")
            else -> if (cp == 9 || cp == 10 || cp == 13 || cp in 0x20..0xD7FF || cp in 0xE000..0xFFFD || cp in 0x10000..0x10FFFF)
                appendCodePoint(cp)
        }
    }
}

private fun ttml(version: TranslationVersion, lines: List<TranslationLine>, original: Boolean, translated: Boolean) = buildString {
    appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
    appendLine("<tt xmlns=\"http://www.w3.org/ns/ttml\" xmlns:ttm=\"http://www.w3.org/ns/ttml#metadata\" xml:space=\"preserve\">")
    appendLine("<head><metadata><ttm:title>${xml(version.title)}</ttm:title><ttm:desc>${xml(version.artist)}</ttm:desc></metadata></head>")
    appendLine("<body><div>")
    lines.forEach { line ->
        append("<p xml:id=\"L${line.index + 1}\" begin=\"${clock(line.begin!!)}\" end=\"${clock(line.end!!)}\">")
        if (original) {
            val words = validWords(line)
            if (words.isEmpty()) append(xml(line.original))
            else words.forEach { word -> append("<span begin=\"${clock(word.begin)}\" end=\"${clock(word.end)}\">${xml(word.text)}</span>") }
        }
        if (translated && line.translation.isNotBlank()) {
            if (original) append("<span ttm:role=\"x-translation\">${xml(line.translation)}</span>")
            else append(xml(line.translation))
        }
        appendLine("</p>")
    }
    append("</div></body></tt>")
}
