package io.github.proify.lyricon.app.ai.explain

import java.text.Normalizer
import java.util.Locale

internal data class LocalAlbumFile(val albumId: Long, val title: String, val artist: String, val order: Int,
    val fileName: String, val relativePath: String, val volume: String) {
    val disc get() = if (order >= 1000) order / 1000 else 1
    val number get() = (order % 1000).takeIf { it > 0 }
        ?: Regex("^(\\d{1,3})[. _-]").find(fileName)?.groupValues?.get(1)?.toIntOrNull()
    val displayTitle get() = title.ifBlank { fileName.substringBeforeLast('.') }
}

private fun normalizedAlbumTag(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC)
    .trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

internal data class LocalAlbumTrackKey(val disc: Int, val number: Int?, val title: String, val artist: String)

internal fun localAlbumTrackKey(file: LocalAlbumFile) = LocalAlbumTrackKey(file.disc, file.number,
    normalizedAlbumTag(file.displayTitle), normalizedAlbumTag(file.artist))

internal fun groupLocalAlbumCopies(files: List<LocalAlbumFile>): List<List<LocalAlbumFile>> =
    files.groupBy { it.albumId }.values.groupBy { album ->
        groupLocalAlbumFiles(album).map { localAlbumTrackKey(it.first()) }.toSet()
    }.values.map { it.flatten() }

/** Keep alternate files for lyric lookup; different discs, track positions and versions stay separate. */
internal fun groupLocalAlbumFiles(files: List<LocalAlbumFile>): List<List<LocalAlbumFile>> {
    val keys = files.map(::localAlbumTrackKey)
    val numbered = keys.filter { it.number != null }.groupBy { Triple(it.disc, it.title, it.artist) }
    return files.groupBy { file ->
        val key = localAlbumTrackKey(file)
        // An untagged copy can use a unique known position, but must not collapse repeated album tracks.
        if (key.number != null) key else numbered[Triple(key.disc, key.title, key.artist)]?.distinct()?.singleOrNull() ?: key
    }.values.map { copies -> copies.sortedWith(compareByDescending<LocalAlbumFile> { it.order % 1000 > 0 }
        .thenBy { it.fileName }.thenBy { it.relativePath }.thenBy { it.volume }) }
        .sortedWith(compareBy<List<LocalAlbumFile>> { it.first().disc }
            .thenBy { it.first().number ?: Int.MAX_VALUE }.thenBy { normalizedAlbumTag(it.first().displayTitle) })
}
