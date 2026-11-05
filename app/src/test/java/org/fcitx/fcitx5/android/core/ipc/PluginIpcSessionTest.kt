/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core.ipc

import org.fcitx.fcitx5.android.common.ipc.PluginIpcHandshake
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginIpcSessionTest {

    private val plugin = "org.example.plugin"

    private fun pending(id: Long, packageName: String = plugin) =
        PluginIpcSession.Pending(id, packageName, "method", null) { _, _ -> }

    @Test
    fun testProtocolVersionOutOfRangeIsIncompatible() {
        val session = PluginIpcSession()
        assertEquals(
            PluginIpcHandshake.Incompatible,
            session.handshake(plugin, plugin, 0)
        )
        assertEquals(
            PluginIpcConnectionState.Incompatible(0),
            session.stateOf(plugin)
        )
        assertEquals(
            PluginIpcHandshake.Incompatible,
            PluginIpcSession().handshake(plugin, plugin, 2)
        )
    }

    @Test
    fun testPluginIdMismatchIsRejected() {
        val session = PluginIpcSession()
        assertEquals(
            PluginIpcHandshake.Rejected,
            session.handshake(plugin, "org.other.plugin", 1)
        )
        assertEquals(
            PluginIpcConnectionState.Rejected("org.other.plugin"),
            session.stateOf(plugin)
        )
    }

    @Test
    fun testAcceptedHandshakeConnects() {
        val session = PluginIpcSession()
        assertEquals(PluginIpcHandshake.Accepted, session.handshake(plugin, plugin, 1))
        assertEquals(PluginIpcConnectionState.Connected(1), session.stateOf(plugin))
    }

    @Test
    fun testTimeoutDropsLateResponse() {
        val session = PluginIpcSession()
        session.enqueue(pending(1))
        val timedOut = session.takeOnTimeout(1)
        assertEquals(1L, timedOut?.requestId)
        assertNull("late reply must be dropped", session.takeOnResponse(1))
    }

    @Test
    fun testLateResponseIsDroppedByRequestId() {
        val session = PluginIpcSession()
        session.enqueue(pending(7))
        assertEquals(7L, session.takeOnResponse(7)?.requestId)
        assertNull(session.takeOnResponse(7))
        assertNull(session.takeOnResponse(8))
    }

    @Test
    fun testCancelRemovesRequest() {
        val session = PluginIpcSession()
        session.enqueue(pending(3))
        assertEquals(3L, session.takeOnCancel(3)?.requestId)
        assertEquals(0, session.pendingCount())
        assertNull("a cancelled request must not be delivered again", session.takeOnResponse(3))
    }

    @Test
    fun testServiceDeathFailsOnlyItsOwnRequests() {
        val session = PluginIpcSession()
        session.enqueue(pending(1))
        session.enqueue(pending(2))
        session.enqueue(pending(3, "org.other.plugin"))
        val failed = session.takeAllOf(plugin)
        assertEquals(listOf(1L, 2L), failed.map { it.requestId })
        assertEquals(1, session.pendingCount())
        assertTrue(session.takeAllOf("org.other.plugin").isNotEmpty())
    }
}
