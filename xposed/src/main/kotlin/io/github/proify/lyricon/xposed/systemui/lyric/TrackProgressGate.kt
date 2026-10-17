package io.github.proify.lyricon.xposed.systemui.lyric

/** Keeps a new media session's progress away from the preceding song's lyrics. */
internal class TrackProgressGate {
    private data class Track(val title: String, val artist: String)

    private var mediaTrack: Track? = null
    private var lyricTrack: Track? = null
    private var matchedMedia = false

    val waitingForLyrics: Boolean
        get() = matchedMedia && mediaTrack != null && lyricTrack != null &&
                !matches(mediaTrack!!, lyricTrack!!)

    fun updateMedia(title: String?, artist: String?) {
        mediaTrack = track(title, artist)
        if (mediaTrack != null && lyricTrack != null && matches(mediaTrack!!, lyricTrack!!)) {
            matchedMedia = true
        }
    }

    fun acceptLyrics(title: String?, artist: String?): Boolean {
        val incoming = track(title, artist)
        if (waitingForLyrics && incoming != null && !matches(mediaTrack!!, incoming)) return false
        lyricTrack = incoming
        if (mediaTrack != null && incoming != null && matches(mediaTrack!!, incoming)) {
            matchedMedia = true
        }
        return true
    }

    fun reset() {
        mediaTrack = null
        lyricTrack = null
        matchedMedia = false
    }

    private fun track(title: String?, artist: String?): Track? {
        val name = title?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return Track(name, artist?.trim().orEmpty())
    }

    private fun matches(first: Track, second: Track): Boolean =
        first.title.equals(second.title, ignoreCase = true) &&
                (first.artist.isEmpty() || second.artist.isEmpty() ||
                        first.artist.equals(second.artist, ignoreCase = true))
}
