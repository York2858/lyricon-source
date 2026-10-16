/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.central.internal.player

import io.github.proify.lyricon.provider.ProviderInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivePlayerSelectorTest {

    private fun session(id: String, playing: Boolean) =
        PlayerSession(ProviderInfo("pkg-" + id, "player-" + id)).apply { isPlaying = playing }

    // ---- 先到先得 ----

    @Test
    fun firstEventBecomesActive() {
        val selector = ActivePlayerSelector()
        val s1 = session("a", playing = true)

        val decision = selector.decide(s1)

        assertTrue(decision is ActivePlayerDecision.Switch)
        decision as ActivePlayerDecision.Switch
        assertSame(s1, decision.session)
        assertTrue(decision.broadcastOriginal)
        assertSame(s1, selector.activeSession)
        assertTrue(selector.activeIsPlaying)
    }

    @Test
    fun sameSessionFollowingEventIsKeep() {
        val selector = ActivePlayerSelector()
        val s1 = session("a", playing = true)
        selector.decide(s1)

        s1.isPlaying = false
        val decision = selector.decide(s1)

        assertEquals(ActivePlayerDecision.Keep, decision)
        assertFalse(selector.activeIsPlaying)
    }

    @Test
    fun switchedAwaySessionEventsAreIgnored() {
        val selector = ActivePlayerSelector()
        val s1 = session("a", playing = false)
        val s2 = session("b", playing = true)
        selector.decide(s1)
        selector.decide(s2)

        val decision = selector.decide(s1)

        assertEquals(ActivePlayerDecision.Ignore, decision)
        assertSame(s2, selector.activeSession)
    }

    // ---- 播放抢占 ----

    @Test
    fun playingProviderPreemptsIdleActiveProvider() {
        val selector = ActivePlayerSelector()
        val idle = session("a", playing = false)
        val playing = session("b", playing = true)
        selector.decide(idle)

        val decision = selector.decide(playing)

        assertTrue(decision is ActivePlayerDecision.Switch)
        decision as ActivePlayerDecision.Switch
        assertSame(playing, decision.session)
        assertSame(playing, selector.activeSession)
    }

    @Test
    fun idleProviderDoesNotPreemptPlayingActiveProvider() {
        val selector = ActivePlayerSelector()
        selector.decide(session("a", playing = true))

        val decision = selector.decide(session("b", playing = true))

        assertEquals(ActivePlayerDecision.Ignore, decision)
        assertTrue(selector.activeIsPlaying)
    }

    @Test
    fun idleProviderDoesNotPreemptIdleActiveProvider() {
        val selector = ActivePlayerSelector()
        selector.decide(session("a", playing = false))

        val decision = selector.decide(session("b", playing = false))

        assertEquals(ActivePlayerDecision.Ignore, decision)
        assertFalse(selector.activeIsPlaying)
    }

    // ---- 切换时的增量取舍 ----

    @Test
    fun duplicateSuppressedWhenFlagDisabled() {
        val selector = ActivePlayerSelector()
        selector.decide(session("a", playing = false))

        val decision = selector.decide(session("b", playing = true), allowDuplicateIfSwitching = false)

        assertTrue(decision is ActivePlayerDecision.Switch)
        assertFalse((decision as ActivePlayerDecision.Switch).broadcastOriginal)
    }

    // ---- 释放 ----

    @Test
    fun releaseMatchingActiveProviderClears() {
        val selector = ActivePlayerSelector()
        val active = session("a", playing = true)
        selector.decide(active)

        val released = selector.release(active.providerInfo)

        assertTrue(released)
        assertNull(selector.activeSession)
        assertFalse(selector.activeIsPlaying)
    }

    @Test
    fun releaseNonActiveProviderIsIgnored() {
        val selector = ActivePlayerSelector()
        selector.decide(session("a", playing = true))

        val released = selector.release(ProviderInfo("pkg-x", "player-x"))

        assertFalse(released)
        assertTrue(selector.activeIsPlaying)
    }

    // ---- 抢占后的状态跟随 ----

    @Test
    fun activePlayingStateFollowsPreemptingSession() {
        val selector = ActivePlayerSelector()
        val idle = session("a", playing = false)
        selector.decide(idle)

        val playing = session("b", playing = true)
        selector.decide(playing)

        assertSame(playing.providerInfo, selector.activeSession?.providerInfo)
        assertTrue(selector.activeIsPlaying)
    }
}
