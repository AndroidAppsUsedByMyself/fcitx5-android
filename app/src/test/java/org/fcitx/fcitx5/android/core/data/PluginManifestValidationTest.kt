/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core.data

import org.fcitx.fcitx5.android.common.plugin.PluginManifestRejection
import org.fcitx.fcitx5.android.common.plugin.PluginManifestResult
import org.fcitx.fcitx5.android.common.plugin.PluginManifestValidator
import org.fcitx.fcitx5.android.common.plugin.PluginProvides
import org.fcitx.fcitx5.android.common.plugin.PluginService
import org.fcitx.fcitx5.android.common.plugin.RawPluginManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginManifestValidationTest {

    private fun rejectionOf(raw: RawPluginManifest): PluginManifestRejection =
        (PluginManifestValidator.validate(raw) as PluginManifestResult.Invalid).rejection

    @Test
    fun testMissingApiVersionRejected() {
        assertEquals(
            PluginManifestRejection.MissingApiVersion,
            rejectionOf(RawPluginManifest(description = "plugin"))
        )
    }

    @Test
    fun testMalformedApiVersionRejected() {
        assertEquals(
            PluginManifestRejection.InvalidApiVersion,
            rejectionOf(RawPluginManifest(apiVersion = "one"))
        )
    }

    @Test
    fun testLegacyManifestWithoutCapabilitiesAccepted() {
        val result = PluginManifestValidator.validate(
            RawPluginManifest(apiVersion = "0.1", description = "legacy plugin")
        )
        assertTrue(result is PluginManifestResult.Parsed)
    }

    @Test
    fun testReservedDomainRejected() {
        assertEquals(
            PluginManifestRejection.ReservedDomain,
            rejectionOf(RawPluginManifest(apiVersion = "0.1", domain = "fcitx5"))
        )
    }

    @Test
    fun testInvalidDomainRejected() {
        assertEquals(
            PluginManifestRejection.InvalidDomain,
            rejectionOf(RawPluginManifest(apiVersion = "0.1", domain = "fcitx5 rime"))
        )
    }

    @Test
    fun testServiceUnavailableWhenComponentMissing() {
        assertFalse(verifyServiceDeclaration(componentExists = false, actionExists = true))
        assertTrue(verifyServiceDeclaration(componentExists = true, actionExists = true))
    }

    @Test
    fun testInvalidDataRootRejected() {
        assertEquals(
            PluginManifestRejection.InvalidDataRoot,
            rejectionOf(
                RawPluginManifest(
                    apiVersion = "0.1",
                    provides = PluginProvides(
                        dataRoots = listOf("../escape")
                    )
                )
            )
        )
    }

    @Test
    fun testServiceDeclarationRequiresComponentAndAction() {
        assertEquals(
            PluginManifestRejection.InvalidService,
            rejectionOf(
                RawPluginManifest(
                    apiVersion = "0.1",
                    service = PluginService(component = "", action = "x", protocolVersion = 1)
                )
            )
        )
    }
}
