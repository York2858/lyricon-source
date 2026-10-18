package io.proify.lyricon.app

import io.github.proify.lyricon.app.ai.explain.*
import io.github.proify.lyricon.lyric.ai.core.AiConfig
import org.junit.Assert.*
import org.junit.Test

class AlbumExplainModelsTest {
    @Test fun manualListPreservesTrackNumbersDiscsAndTitles() {
        val tracks = parseAlbumTrackList("3.Holy, Holy\n4. The New Sound\n2-01. Intro\nThe 1975")
        assertEquals(listOf(3, 4, 1, 4), tracks.map { it.number })
        assertEquals(listOf("Holy, Holy", "The New Sound", "Intro", "The 1975"), tracks.map { it.title })
        assertEquals(2, tracks[2].disc)
    }
    @Test fun rejectsEmptyOrUnboundedAlbums() {
        listOf("  \n", (1..61).joinToString("\n") { "$it. Song" }).forEach { text ->
            try { parseAlbumTrackList(text); fail("Should reject invalid album") } catch (_: IllegalArgumentException) {}
        }
    }
    @Test fun perTrackPromptRequiresLyricsAndArrangementWithoutInventingTechnicalMeasurements() {
        val track = AlbumExplainTrack(number = 3, title = "Holy, Holy", lyrics = "provided lyric", notes = "用户提供的切分与铜管听感")
        val album = AlbumExplainRecord(title = "The New Sound", artist = "Geordie Greep", tracks = listOf(track))
        val user = AlbumExplainPrompt.user(album, track, "zh-CN")
        assertTrue(user.contains("本次歌曲：3. Holy, Holy")); assertTrue(user.contains("provided lyric"))
        val system = AlbumExplainPrompt.system()
        listOf("歌词与表达", "编曲与律动", "拍号", "三连音", "没有音频输入", "不能仅凭复杂律动").forEach { assertTrue(it, system.contains(it)) }
        val instrumental = AlbumExplainPrompt.user(album, track.copy(instrumental = true), "zh-CN")
        assertFalse(instrumental.contains("provided lyric")); assertTrue(instrumental.contains("不分析歌词"))
    }
    @Test fun cacheSeparatesMusicalReferencesTrackOrderAndLyrics() {
        val track = AlbumExplainTrack(number = 3, title = "Holy, Holy")
        val album = AlbumExplainRecord(title = "The New Sound", artist = "Geordie Greep", tracks = listOf(track))
        val config = AiConfig("secret-test-key", "fixture", "https://example.test")
        fun key(a: AlbumExplainRecord = album, t: AlbumExplainTrack = track) = albumExplainCacheKey(a, t, config, "system", "zh-CN")
        listOf(key(t = track.copy(notes = "5/8（用户提供）")), key(t = track.copy(lyrics = "lyrics")), key(t = track.copy(instrumental = true)),
            key(a = album.copy(tracks = listOf(track.copy(number = 1))))).forEach { assertNotEquals(key(), it) }
        assertFalse(key().contains("secret-test-key"))
    }
    @Test fun lyricTextRemovesLrcMetadataAndBothTimestampStyles() {
        assertEquals("hello world\n译文", plainAlbumLyrics("[ar:artist]\n[01:02.300]<01:02.300>hello <01:03.000>world<01:04.000>\n[01:02.300]译文[01:04.000]", "lrc"))
    }
}
