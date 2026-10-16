/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.provider.internal.player

import android.media.session.PlaybackState
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.RemotePlayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResyncingPlayerTest {

    // ---- 手动模式重放 ----

    @Test
    fun replayManualSnapshotInOriginalOrder() {
        val channel = RecordingPlayer()
        val player = ResyncingPlayer(channel)

        player.setPositionUpdateInterval(40)
        player.setDisplayTranslation(true)
        player.setDisplayRoma(true)
        player.setSong(Song(name = "demo"))
        player.setPlaybackState(true)
        player.setPosition(5000)

        channel.calls.clear()
        player.sync()

        assertEquals(
            listOf(
                "setPositionUpdateInterval:40",
                "setDisplayTranslation:true",
                "setDisplayRoma:true",
                "setSong:demo",
                "setPlaybackState(boolean):true",
                "seekTo:5000",
            ),
            channel.calls
        )
    }

    @Test
    fun replaySongClearedAsNullAfterText() {
        val channel = RecordingPlayer()
        val player = ResyncingPlayer(channel)

        player.setSong(Song(name = "demo"))
        player.sendText("hello")

        channel.calls.clear()
        player.sync()

        // 默认手动模式仍会重放播放状态与位置（与原有实现一致）
        assertEquals(
            listOf("sendText:hello", "setPlaybackState(boolean):false", "seekTo:0"),
            channel.calls
        )
    }

    @Test
    fun replayEmptySnapshotDoesNothing() {
        val channel = RecordingPlayer()
        val player = ResyncingPlayer(channel)

        player.sync()

        // 空快照在手动模式下仍会重放默认播放状态与位置（与原有实现一致）
        assertEquals(
            listOf("setPlaybackState(boolean):false", "seekTo:0"),
            channel.calls
        )
    }

    // ---- 状态驱动模式 ----

    @Test
    fun stateDrivenModeKeepsManualContextAcrossSwitches() {
        val channel = RecordingPlayer()
        val player = ResyncingPlayer(channel)

        player.setPlaybackState(true)
        player.setPosition(1000)
        player.setPlaybackState(null as PlaybackState?)
        player.setPlaybackState(false)

        channel.calls.clear()
        player.sync()

        assertEquals(
            listOf("setPlaybackState(boolean):false", "seekTo:1000"),
            channel.calls
        )
    }

    @Test
    fun positionUpdateDoesNotSwitchAwayFromStateDriven() {
        val channel = RecordingPlayer()
        val player = ResyncingPlayer(channel)

        player.setPlaybackState(null as PlaybackState?)
        player.setPosition(123)

        channel.calls.clear()
        player.sync()

        assertEquals(listOf("setPlaybackState(state):null"), channel.calls)
    }

    @Test
    fun seekDoesNotSwitchAwayFromStateDriven() {
        val channel = RecordingPlayer()
        val player = ResyncingPlayer(channel)

        player.setPlaybackState(null as PlaybackState?)
        player.seekTo(456)

        channel.calls.clear()
        player.sync()

        assertEquals(listOf("setPlaybackState(state):null"), channel.calls)
    }

    @Test
    fun stateDrivenReplaySendsPlaybackState() {
        val channel = RecordingPlayer()
        val player = ResyncingPlayer(channel)

        player.setPositionUpdateInterval(50)
        player.setSong(Song(name = "demo"))
        player.setPlaybackState(null as PlaybackState?)

        channel.calls.clear()
        player.sync()

        assertEquals(
            listOf(
                "setPositionUpdateInterval:50",
                "setSong:demo",
                "setPlaybackState(state):null",
            ),
            channel.calls
        )
    }

    // ---- 转发即时性 ----

    @Test
    fun writesAreForwardedToChannelImmediately() {
        val channel = RecordingPlayer()
        val player = ResyncingPlayer(channel)

        player.setSong(Song(name = "demo"))
        player.setPlaybackState(true)
        player.setPosition(2000)

        assertTrue(channel.calls.contains("setSong:demo"))
        assertTrue(channel.calls.contains("setPlaybackState(boolean):true"))
        assertTrue(channel.calls.contains("setPosition:2000"))
    }

    @Test
    fun negativePositionsAreCoercedToZero() {
        val channel = RecordingPlayer()
        val player = ResyncingPlayer(channel)

        player.setPosition(-100)

        channel.calls.clear()
        player.sync()

        assertEquals(
            listOf("setPlaybackState(boolean):false", "seekTo:0"),
            channel.calls
        )
    }

    /** 记录所有调用的假远端通道。 */
    private class RecordingPlayer : RemotePlayer {
        val calls = mutableListOf<String>()

        override val isActive: Boolean get() = false

        override fun setSong(song: Song?): Boolean {
            calls += "setSong:" + (song?.name ?: "null")
            return true
        }

        override fun setPlaybackState(playing: Boolean): Boolean {
            calls += "setPlaybackState(boolean):" + playing
            return true
        }

        override fun seekTo(position: Long): Boolean {
            calls += "seekTo:" + position
            return true
        }

        override fun setPosition(position: Long): Boolean {
            calls += "setPosition:" + position
            return true
        }

        override fun setPositionUpdateInterval(interval: Int): Boolean {
            calls += "setPositionUpdateInterval:" + interval
            return true
        }

        override fun sendText(text: String?): Boolean {
            calls += "sendText:" + (text ?: "null")
            return true
        }

        override fun setDisplayTranslation(isDisplayTranslation: Boolean): Boolean {
            calls += "setDisplayTranslation:" + isDisplayTranslation
            return true
        }

        override fun setDisplayRoma(isDisplayRoma: Boolean): Boolean {
            calls += "setDisplayRoma:" + isDisplayRoma
            return true
        }

        override fun setPlaybackState(state: PlaybackState?): Boolean {
            calls += "setPlaybackState(state):" + (state?.toString() ?: "null")
            return true
        }
    }
}
