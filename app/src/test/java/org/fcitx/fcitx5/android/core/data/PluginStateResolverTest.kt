/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core.data

import org.fcitx.fcitx5.android.common.plugin.PluginProvides
import org.fcitx.fcitx5.android.common.plugin.PluginService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginStateResolverTest {

    private fun descriptor(
        addons: List<String> = emptyList(),
        service: PluginService? = null,
        hasService: Boolean = false
    ) = PluginDescriptor(
        packageName = "org.example.plugin",
        matchedAction = "org.example.plugin.MANIFEST",
        origin = PluginOrigin.ThirdParty,
        apiVersion = "0.1",
        domain = null,
        description = "",
        provides = if (addons.isEmpty()) null else PluginProvides(addons = addons),
        service = service,
        hasService = hasService,
        versionName = "",
        nativeLibraryDir = ""
    )

    private fun resolve(
        descriptor: PluginDescriptor,
        loaded: Set<String> = emptySet(),
        onDemand: Set<String> = emptySet(),
        serviceBound: Boolean = false
    ) = PluginStateResolver.resolve(
        descriptor,
        AddonRuntimeSnapshot(loaded, onDemand),
        serviceBound
    )

    @Test
    fun testMergedButAddonNotLoaded() {
        val state = resolve(descriptor(addons = listOf("rime")))
        assertEquals(AddonStatus.NotLoaded, state.addons.getValue("rime"))
    }

    @Test
    fun testOnDemandAddonIsAvailableNotLoaded() {
        val state = resolve(descriptor(addons = listOf("rime")), onDemand = setOf("rime"))
        assertEquals(AddonStatus.AvailableNotLoaded, state.addons.getValue("rime"))
    }

    @Test
    fun testLoadedAddon() {
        val state = resolve(descriptor(addons = listOf("rime")), loaded = setOf("rime"))
        assertEquals(AddonStatus.Loaded, state.addons.getValue("rime"))
    }

    @Test
    fun testServiceDeclaredButNotBound() {
        val state = resolve(
            descriptor(service = PluginService(".MainService", "action", 7)),
            serviceBound = false
        )
        assertEquals(PluginServiceState.NotBound(7), state.service)
    }

    @Test
    fun testLegacyServiceHasUnknownProtocol() {
        val state = resolve(descriptor(hasService = true), serviceBound = false)
        assertEquals(PluginServiceState.NotBound(null), state.service)
    }

    @Test
    fun testServiceNotDeclared() {
        val state = resolve(descriptor(), serviceBound = true)
        assertEquals(PluginServiceState.NotDeclared, state.service)
    }

    @Test
    fun testFailedState() {
        val state: PluginState = PluginState.Failed("org.example.plugin", PluginLoadFailed.MissingPluginDescriptor)
        assertTrue(state is PluginState.Failed)
    }
}
