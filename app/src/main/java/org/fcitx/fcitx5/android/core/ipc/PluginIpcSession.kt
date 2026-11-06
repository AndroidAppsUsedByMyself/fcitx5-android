/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core.ipc

import org.fcitx.fcitx5.android.common.ipc.PluginIpcHandshake
import org.fcitx.fcitx5.android.common.ipc.PluginIpcPolicy
import org.fcitx.fcitx5.android.common.ipc.PluginIpcResult

/**
 * Connection state of the IPC service declared by one plugin package.
 */
sealed interface PluginIpcConnectionState {
    data object Idle : PluginIpcConnectionState
    data object Handshaking : PluginIpcConnectionState
    data class Connected(val protocolVersion: Int) : PluginIpcConnectionState
    data class Incompatible(val protocolVersion: Int) : PluginIpcConnectionState
    data class Rejected(val pluginId: String) : PluginIpcConnectionState
}

/**
 * Request bookkeeping for [FcitxPluginServices].
 *
 * Pure Kotlin, so the lifecycle rules (single reply, late replies dropped, cancel and
 * disconnect outcomes) can be unit tested without Android binder objects.
 */
class PluginIpcSession {

    data class Pending(
        val requestId: Long,
        val packageName: String,
        val method: String,
        val params: ByteArray?,
        val deliver: (PluginIpcResult, ByteArray?) -> Unit
    )

    private val states = mutableMapOf<String, PluginIpcConnectionState>()
    private val pending = LinkedHashMap<Long, Pending>()

    @Synchronized
    fun stateOf(packageName: String): PluginIpcConnectionState =
        states[packageName] ?: PluginIpcConnectionState.Idle

    @Synchronized
    fun markHandshaking(packageName: String) {
        states[packageName] = PluginIpcConnectionState.Handshaking
    }

    @Synchronized
    fun markUnbound(packageName: String) {
        states[packageName] = PluginIpcConnectionState.Idle
    }

    /**
     * Record the handshake result. Requests must only be sent when this returns
     * [PluginIpcHandshake.Accepted].
     */
    @Synchronized
    fun handshake(
        packageName: String,
        pluginId: String,
        protocolVersion: Int
    ): PluginIpcHandshake {
        val result = PluginIpcPolicy.check(pluginId, packageName, protocolVersion)
        states[packageName] = when (result) {
            PluginIpcHandshake.Accepted -> PluginIpcConnectionState.Connected(protocolVersion)
            PluginIpcHandshake.Incompatible ->
                PluginIpcConnectionState.Incompatible(protocolVersion)
            PluginIpcHandshake.Rejected -> PluginIpcConnectionState.Rejected(pluginId)
        }
        return result
    }

    @Synchronized
    fun enqueue(request: Pending) {
        pending[request.requestId] = request
    }

    /** Remove a request whose reply arrived; `null` means the reply is late and must be dropped. */
    @Synchronized
    fun takeOnResponse(requestId: Long): Pending? = pending.remove(requestId)

    @Synchronized
    fun takeOnTimeout(requestId: Long): Pending? = pending.remove(requestId)

    @Synchronized
    fun takeOnCancel(requestId: Long): Pending? = pending.remove(requestId)

    /** Requests queued for [packageName] while its service was not connected yet. */
    @Synchronized
    fun pendingOf(packageName: String): List<Pending> =
        pending.values.filter { it.packageName == packageName }

    /** Remove every request belonging to [packageName], e.g. when its service dies. */
    @Synchronized
    fun takeAllOf(packageName: String): List<Pending> {
        val removed = pending.values.filter { it.packageName == packageName }
        removed.forEach { pending.remove(it.requestId) }
        return removed
    }

    @Synchronized
    fun pendingCount(): Int = pending.size
}
