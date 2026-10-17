package io.github.proify.lyricon.lyric.model

/** Translation and display-only changes do not make a different track. */
fun Song?.isSameTrackAs(other: Song?): Boolean {
    if (this === other) return true
    if (this == null || other == null) return false
    val firstId = id?.takeIf { it.isNotBlank() }
    val secondId = other.id?.takeIf { it.isNotBlank() }
    if (firstId != null && secondId != null) return firstId == secondId
    val firstName = name?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    return firstName == other.name?.trim() && artist?.trim().orEmpty() == other.artist?.trim().orEmpty()
}
