/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.provider.internal.connection

import io.github.proify.lyricon.provider.ConnectionStatus
import io.github.proify.lyricon.provider.isConnected
import io.github.proify.lyricon.provider.internal.connection.ConnectionNotice.CONNECTED
import io.github.proify.lyricon.provider.internal.connection.ConnectionNotice.CONNECT_TIMEOUT
import io.github.proify.lyricon.provider.internal.connection.ConnectionNotice.DISCONNECTED
import io.github.proify.lyricon.provider.internal.connection.ConnectionNotice.RECONNECTED
import io.github.proify.lyricon.provider.internal.connection.ConnectionTrigger.PREEMPT
import io.github.proify.lyricon.provider.internal.connection.ConnectionTrigger.REGISTER
import io.github.proify.lyricon.provider.internal.connection.ConnectionTrigger.REGISTRATION_TIMEOUT
import io.github.proify.lyricon.provider.internal.connection.ConnectionTrigger.SERVICE_LOST
import io.github.proify.lyricon.provider.internal.connection.ConnectionTrigger.SERVICE_READY
import io.github.proify.lyricon.provider.internal.connection.ConnectionTrigger.USER_DISCONNECT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionStateMachineTest {

    // ---- 注册 ----

    @Test
    fun registerAllowedFromAnyDisconnectedState() {
        listOf(
            ConnectionStatus.DISCONNECTED,
            ConnectionStatus.DISCONNECTED_REMOTE,
            ConnectionStatus.DISCONNECTED_USER,
        ).forEach { from ->
            val machine = ConnectionStateMachine(initialStatus = from)

            val result = machine.on(REGISTER)

            assertEquals(ConnectionStatus.CONNECTING, result.status)
            assertNull(result.notice)
        }
    }

    @Test
    fun registerRejectedWhileConnectingOrConnected() {
        listOf(
            ConnectionStatus.CONNECTING,
            ConnectionStatus.CONNECTED,
        ).forEach { from ->
            val machine = ConnectionStateMachine(initialStatus = from)

            val result = machine.on(REGISTER)

            assertEquals(from, result.status)
            assertNull(result.notice)
        }
    }

    // ---- 超时 ----

    @Test
    fun timeoutWhileConnectingYieldsDisconnectedAndNotice() {
        val machine = ConnectionStateMachine()
        machine.on(REGISTER)

        val result = machine.on(REGISTRATION_TIMEOUT)

        assertEquals(ConnectionStatus.DISCONNECTED, result.status)
        assertEquals(CONNECT_TIMEOUT, result.notice)
    }

    @Test
    fun timeoutIgnoredWhenNotConnecting() {
        val machine = ConnectionStateMachine(
            initialStatus = ConnectionStatus.DISCONNECTED
        )

        val result = machine.on(REGISTRATION_TIMEOUT)

        assertEquals(ConnectionStatus.DISCONNECTED, result.status)
        assertNull(result.notice)
    }

    // ---- 连接成功 ----

    @Test
    fun firstServiceReadyYieldsConnectedNotice() {
        val machine = ConnectionStateMachine()
        machine.on(REGISTER)

        val result = machine.on(SERVICE_READY)

        assertEquals(ConnectionStatus.CONNECTED, result.status)
        assertEquals(CONNECTED, result.notice)
        assertTrue(machine.hasConnectedBefore)
    }

    @Test
    fun serviceReadyAfterPreviousConnectionYieldsReconnectedNotice() {
        val machine = ConnectionStateMachine()
        machine.on(REGISTER)
        machine.on(SERVICE_READY)
        machine.on(SERVICE_LOST, serviceWasBound = true)

        machine.on(REGISTER)
        val result = machine.on(SERVICE_READY)

        assertEquals(ConnectionStatus.CONNECTED, result.status)
        assertEquals(RECONNECTED, result.notice)
    }

    // ---- 断开 ----

    @Test
    fun serviceLostWithBoundServiceYieldsDisconnectedNotice() {
        val machine = ConnectionStateMachine()
        machine.on(REGISTER)
        machine.on(SERVICE_READY)

        val result = machine.on(SERVICE_LOST, serviceWasBound = true)

        assertEquals(ConnectionStatus.DISCONNECTED_REMOTE, result.status)
        assertEquals(DISCONNECTED, result.notice)
    }

    @Test
    fun serviceLostWithoutBoundServiceYieldsNoNotice() {
        val machine = ConnectionStateMachine()

        val result = machine.on(SERVICE_LOST, serviceWasBound = false)

        assertEquals(ConnectionStatus.DISCONNECTED_REMOTE, result.status)
        assertNull(result.notice)
    }

    @Test
    fun userDisconnectWithBoundServiceYieldsUserStatusAndNotice() {
        val machine = ConnectionStateMachine()
        machine.on(REGISTER)
        machine.on(SERVICE_READY)

        val result = machine.on(USER_DISCONNECT, serviceWasBound = true)

        assertEquals(ConnectionStatus.DISCONNECTED_USER, result.status)
        assertEquals(DISCONNECTED, result.notice)
    }

    @Test
    fun userDisconnectWhileConnectingYieldsUserStatusWithoutNotice() {
        val machine = ConnectionStateMachine()
        machine.on(REGISTER)

        val result = machine.on(USER_DISCONNECT, serviceWasBound = false)

        assertEquals(ConnectionStatus.DISCONNECTED_USER, result.status)
        assertNull(result.notice)
    }

    // ---- 替换 ----

    @Test
    fun preemptWithBoundServiceYieldsDisconnectedNotice() {
        val machine = ConnectionStateMachine()
        machine.on(REGISTER)
        machine.on(SERVICE_READY)

        val result = machine.on(PREEMPT, serviceWasBound = true)

        assertEquals(ConnectionStatus.DISCONNECTED, result.status)
        assertEquals(DISCONNECTED, result.notice)
    }

    @Test
    fun preemptWithoutBoundServiceYieldsNoNotice() {
        val machine = ConnectionStateMachine()

        val result = machine.on(PREEMPT, serviceWasBound = false)

        assertEquals(ConnectionStatus.DISCONNECTED, result.status)
        assertNull(result.notice)
    }

    // ---- 完整生命周期 ----

    @Test
    fun reconnectAfterRemoteLossKeepsHistoryAndReconnectedNotice() {
        val machine = ConnectionStateMachine()
        machine.on(REGISTER)
        machine.on(SERVICE_READY)
        assertTrue(machine.hasConnectedBefore)

        machine.on(SERVICE_LOST, serviceWasBound = true)
        assertFalse(machine.status.isConnected())

        machine.on(REGISTER)
        val result = machine.on(SERVICE_READY)

        assertEquals(RECONNECTED, result.notice)
        assertTrue(machine.hasConnectedBefore)
    }
}
