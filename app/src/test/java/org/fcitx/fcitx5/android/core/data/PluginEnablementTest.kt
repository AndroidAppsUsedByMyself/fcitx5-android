/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginEnablementTest {

    private fun descriptor(packageName: String, origin: PluginOrigin) = PluginDescriptor(
        packageName = packageName,
        matchedAction = "org.example.plugin.MANIFEST",
        origin = origin,
        apiVersion = "0.1",
        domain = null,
        description = "",
        provides = null,
        service = null,
        hasService = false,
        versionName = "",
        nativeLibraryDir = ""
    )

    @Test
    fun testDisabledPluginIsNotMerged() {
        val plugin = descriptor("org.example.thirdparty", PluginOrigin.ThirdParty)
        val enabled = PluginEnablement.filterEnabled(listOf(plugin), emptySet())
        assertTrue(enabled.isEmpty())
    }

    @Test
    fun testEnabledPluginIsMerged() {
        val plugin = descriptor("org.example.official", PluginOrigin.Official)
        val enabled = PluginEnablement.filterEnabled(listOf(plugin), setOf(plugin.packageName))
        assertEquals(listOf(plugin), enabled)
    }

    @Test
    fun testThirdPartyIsNotAutoEnabled() {
        val plugin = descriptor("org.example.thirdparty", PluginOrigin.ThirdParty)
        val state = PluginEnablement.resolve(listOf(plugin), emptySet(), emptySet())
        assertFalse(plugin.packageName in state.enabled)
        assertTrue(plugin.packageName in state.known)
    }

    @Test
    fun testTrustedPluginIsAutoEnabledOnFirstDiscovery() {
        val official = descriptor("org.example.official", PluginOrigin.Official)
        val downstream = descriptor("org.example.downstream", PluginOrigin.Downstream)
        val state = PluginEnablement.resolve(listOf(official, downstream), emptySet(), emptySet())
        assertTrue(official.packageName in state.enabled)
        assertTrue(downstream.packageName in state.enabled)
    }

    @Test
    fun testDisabledTrustedPluginStaysDisabled() {
        val plugin = descriptor("org.example.official", PluginOrigin.Official)
        val state = PluginEnablement.resolve(
            listOf(plugin),
            enabled = emptySet(),
            known = setOf(plugin.packageName)
        )
        assertFalse(plugin.packageName in state.enabled)
    }

    @Test
    fun testChangedSignerDisablesEnabledPlugin() {
        val plugin = descriptor("org.example.plugin", PluginOrigin.Downstream)
        val state = PluginEnablement.resolve(
            listOf(plugin),
            enabled = setOf(plugin.packageName),
            known = setOf(plugin.packageName),
            blocked = setOf(plugin.packageName)
        )
        assertFalse(plugin.packageName in state.enabled)
        assertTrue(plugin.packageName in state.known)
    }

    @Test
    fun testChangedSignerIsNotAutoEnabled() {
        val plugin = descriptor("org.example.plugin", PluginOrigin.Official)
        val state = PluginEnablement.resolve(
            listOf(plugin),
            enabled = emptySet(),
            known = setOf(plugin.packageName),
            blocked = setOf(plugin.packageName)
        )
        assertFalse(plugin.packageName in state.enabled)
    }

    @Test
    fun testToggleTakesEffectByPackageName() {
        val first = descriptor("org.example.first", PluginOrigin.ThirdParty)
        val second = descriptor("org.example.second", PluginOrigin.ThirdParty)
        val plugins = listOf(first, second)
        assertEquals(
            listOf(first),
            PluginEnablement.filterEnabled(plugins, setOf(first.packageName))
        )
        assertEquals(
            listOf(second),
            PluginEnablement.filterEnabled(plugins, setOf(second.packageName))
        )
    }
}
