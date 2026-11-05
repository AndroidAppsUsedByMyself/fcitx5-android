/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.common.ipc

/**
 * Host to plugin IPC protocol.
 *
 * The status codes are mirrored by `AndroidIpcStatus` in
 * `app/src/main/cpp/androidipcbridge/androidipcbridge.h`; keep both in sync.
 */
object PluginIpcProtocol {

    const val VERSION = 1

    const val MIN_VERSION = 1

    const val MAX_VERSION = 1

    const val TIMEOUT_MILLIS = 500L

    const val SERVICE_ACTION_SUFFIX = ".plugin.SERVICE"
}

enum class PluginIpcResult(val code: Int) {
    Ok(0),
    Unavailable(1),
    Timeout(2),
    Cancelled(3),
    ProtocolIncompatible(4),
    Rejected(5);

    companion object {
        fun fromCode(code: Int): PluginIpcResult? = entries.firstOrNull { it.code == code }
    }
}

enum class PluginIpcHandshake {
    Accepted,
    Incompatible,
    Rejected
}

object PluginIpcPolicy {

    /**
     * Validate the handshake reported by a plugin service.
     *
     * [pluginId] is only checked for consistency with the package that was bound; it is
     * never used as a routing key.
     */
    fun check(
        pluginId: String,
        expectedPackageName: String,
        protocolVersion: Int
    ): PluginIpcHandshake = when {
        pluginId != expectedPackageName -> PluginIpcHandshake.Rejected
        protocolVersion < PluginIpcProtocol.MIN_VERSION ||
                protocolVersion > PluginIpcProtocol.MAX_VERSION -> PluginIpcHandshake.Incompatible
        else -> PluginIpcHandshake.Accepted
    }
}
