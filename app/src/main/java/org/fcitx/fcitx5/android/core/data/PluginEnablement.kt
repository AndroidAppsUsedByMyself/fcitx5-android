/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core.data

/**
 * Result of applying the first-discovery policy.
 */
data class PluginEnablementState(
    val enabled: Set<String>,
    val known: Set<String>
)

/**
 * Plugin enablement is keyed by package name.
 *
 * Trusted plugins (official or downstream) are enabled automatically the first time they
 * are discovered. Third-party plugins stay disabled until the user enables them explicitly.
 * Once a package is known, it is never auto-enabled again, so a user's decision to disable
 * a trusted plugin is preserved.
 */
object PluginEnablement {

    fun resolve(
        descriptors: Collection<PluginDescriptor>,
        enabled: Set<String>,
        known: Set<String>
    ): PluginEnablementState {
        val updatedKnown = known.toMutableSet()
        val updatedEnabled = enabled.toMutableSet()
        descriptors.forEach { descriptor ->
            if (descriptor.packageName !in updatedKnown) {
                updatedKnown.add(descriptor.packageName)
                if (descriptor.origin != PluginOrigin.ThirdParty) {
                    updatedEnabled.add(descriptor.packageName)
                }
            }
        }
        return PluginEnablementState(updatedEnabled, updatedKnown)
    }

    fun filterEnabled(
        descriptors: Collection<PluginDescriptor>,
        enabled: Set<String>
    ): List<PluginDescriptor> = descriptors.filter { it.packageName in enabled }
}
