/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core.data

/**
 * Runtime state of one addon declared by a merged plugin.
 */
enum class AddonStatus {
    Loaded,
    AvailableNotLoaded,
    NotLoaded
}

/**
 * Binding state of the IPC service declared by a plugin.
 *
 * [protocolVersion] is `null` when the plugin only uses the legacy `hasService` hint,
 * so the protocol version is unknown.
 */
sealed interface PluginServiceState {
    data object NotDeclared : PluginServiceState

    data class Bound(val protocolVersion: Int?) : PluginServiceState

    data class NotBound(val protocolVersion: Int?) : PluginServiceState
}

/**
 * Snapshot of addon names queried from the running fcitx instance.
 */
data class AddonRuntimeSnapshot(
    val loaded: Set<String> = emptySet(),
    val onDemand: Set<String> = emptySet()
)

/**
 * Runtime state of a plugin.
 *
 * [Active] means the plugin assets were merged; addon and service states are tracked separately.
 */
sealed interface PluginState {
    data class Active(
        val descriptor: PluginDescriptor,
        val addons: Map<String, AddonStatus>,
        val service: PluginServiceState
    ) : PluginState

    data class Failed(
        val packageName: String,
        val reason: PluginLoadFailed
    ) : PluginState
}

object PluginStateResolver {

    fun resolve(
        descriptor: PluginDescriptor,
        runtime: AddonRuntimeSnapshot,
        serviceBound: Boolean
    ): PluginState.Active {
        val addons = descriptor.provides?.addons.orEmpty().distinct().associateWith { name ->
            when {
                name in runtime.loaded -> AddonStatus.Loaded
                name in runtime.onDemand -> AddonStatus.AvailableNotLoaded
                else -> AddonStatus.NotLoaded
            }
        }
        val declaresService = descriptor.service != null || descriptor.hasService
        val protocolVersion = descriptor.service?.protocolVersion
        val service = when {
            !declaresService -> PluginServiceState.NotDeclared
            serviceBound -> PluginServiceState.Bound(protocolVersion)
            else -> PluginServiceState.NotBound(protocolVersion)
        }
        return PluginState.Active(descriptor, addons, service)
    }
}
