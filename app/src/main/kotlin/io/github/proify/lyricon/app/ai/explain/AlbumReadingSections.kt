package io.github.proify.lyricon.app.ai.explain

internal enum class AlbumReadingKind(val title: String) {
    GENERAL(""), LYRICS("歌词与表达"), ARRANGEMENT("编曲与律动"), CONTEXT("在专辑中的位置")
}

internal data class AlbumReadingSection(val kind: AlbumReadingKind, val content: String)

fun albumTrackHeading(track: AlbumExplainTrack): String = "歌曲 ${track.number}：${track.title}"

private val sectionNames = mapOf(
    "歌词与表达" to AlbumReadingKind.LYRICS, "歌词解读" to AlbumReadingKind.LYRICS, "歌词分析" to AlbumReadingKind.LYRICS,
    "编曲与律动" to AlbumReadingKind.ARRANGEMENT, "编曲解读" to AlbumReadingKind.ARRANGEMENT, "编曲分析" to AlbumReadingKind.ARRANGEMENT,
    "在专辑中的位置" to AlbumReadingKind.CONTEXT, "专辑中的位置" to AlbumReadingKind.CONTEXT, "专辑位置" to AlbumReadingKind.CONTEXT)
private val sectionHeading = Regex("^(${sectionNames.keys.joinToString("|")})(?:\\s*[：:]\\s*(.*))?$")
private val decoratedHeading = Regex("^(\\*\\*|__)(.*?)\\1(.*)$")

private fun headingText(line: String): String {
    val text = line.trim().replace(Regex("^#{1,6}\\s*"), "")
        .replace(Regex("^(?:\\d+|[一二三四五六七八九十]+)[.、)）]\\s*"), "")
        .replace(Regex("\\s+#+$"), "")
    val bold = decoratedHeading.matchEntire(text)
    return (if (bold != null) bold.groupValues[2] + bold.groupValues[3] else text).trim()
        .replace(Regex("^(?:\\d+|[一二三四五六七八九十]+)[.、)）]\\s*"), "")
}

/** Split presentation only. Unrecognized prose and fenced Markdown stay intact, including saved results. */
internal fun albumReadingSections(content: String, track: AlbumExplainTrack): List<AlbumReadingSection> {
    val sections = mutableListOf<AlbumReadingSection>()
    var kind = AlbumReadingKind.GENERAL
    val body = StringBuilder()
    var fence: String? = null
    var started = false
    fun flush() {
        val text = body.toString().trim()
        if (text.isNotBlank() || kind != AlbumReadingKind.GENERAL) sections += AlbumReadingSection(kind, text)
        body.clear()
    }
    val oldTitle = "歌曲：${if (track.disc > 1) "CD ${track.disc} · " else ""}${track.number}. ${track.title}"
    for (line in content.lineSequence()) {
        val marker = Regex("^\\s*(`{3,}|~{3,})").find(line)?.groupValues?.get(1)
        if (fence != null) {
            body.appendLine(line)
            if (marker != null && marker.first() == fence.first() && marker.length >= fence.length && line.trim() == marker) fence = null
            continue
        }
        if (marker != null) { fence = marker; body.appendLine(line); started = true; continue }
        val heading = headingText(line)
        if (!started && (heading == oldTitle || heading == albumTrackHeading(track))) continue
        val match = sectionHeading.matchEntire(heading)
        if (match != null) {
            flush(); kind = sectionNames.getValue(match.groupValues[1]); started = true
            if (match.groupValues[2].isNotBlank()) body.appendLine(match.groupValues[2])
        } else {
            body.appendLine(line)
            if (line.isNotBlank()) started = true
        }
    }
    flush()
    return sections
}
