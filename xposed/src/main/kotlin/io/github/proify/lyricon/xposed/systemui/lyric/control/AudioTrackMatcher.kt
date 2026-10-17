package io.github.proify.lyricon.xposed.systemui.lyric.control

import kotlin.math.abs

internal data class AudioTrackIdentity(val title: String, val artist: String,
    val album: String?, val durationMs: Long?)

internal data class LocalAudioTrack(val id: Long, val title: String, val artist: String,
    val album: String?, val durationMs: Long, val mime: String)

/** Never pick an arbitrary same-titled file when handing audio to a tag editor. */
internal object AudioTrackMatcher {
    fun matches(track: AudioTrackIdentity, files: List<LocalAudioTrack>): List<LocalAudioTrack> =
        files.filter { file ->
            file.title.trim().equals(track.title.trim(), ignoreCase = true) &&
                (track.artist.isBlank() || file.artist.trim().equals(track.artist.trim(), ignoreCase = true)) &&
                (track.album.isNullOrBlank() || file.album?.trim().equals(track.album.trim(), ignoreCase = true)) &&
                (track.durationMs == null || track.durationMs <= 0 || file.durationMs <= 0 ||
                    abs(track.durationMs - file.durationMs) <= 1_500)
        }
}
