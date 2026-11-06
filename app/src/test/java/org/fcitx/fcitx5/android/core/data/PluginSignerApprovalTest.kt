/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginSignerApprovalTest {

    private fun descriptor(packageName: String, fingerprint: String?) = PluginDescriptor(
        packageName = packageName,
        matchedAction = PluginManifestActions.OFFICIAL_RELEASE,
        origin = PluginOrigin.ThirdParty,
        apiVersion = "0.1",
        domain = null,
        description = "",
        provides = null,
        service = null,
        hasService = false,
        versionName = "",
        nativeLibraryDir = "",
        signerFingerprint = fingerprint
    )

    @Test
    fun testNewSignerWithoutRecordedApproval() {
        val state = PluginSignerApprovals.evaluate(
            descriptor("org.example.plugin", "fingerprint-a"),
            emptyMap()
        )
        assertEquals(PluginSignatureState.New, state)
    }

    @Test
    fun testMatchingSignerIsApproved() {
        val state = PluginSignerApprovals.evaluate(
            descriptor("org.example.plugin", "fingerprint-a"),
            mapOf("org.example.plugin" to "fingerprint-a")
        )
        assertEquals(PluginSignatureState.Approved, state)
    }

    @Test
    fun testChangedSignerRevokesTrust() {
        val state = PluginSignerApprovals.evaluate(
            descriptor("org.example.plugin", "fingerprint-b"),
            mapOf("org.example.plugin" to "fingerprint-a")
        )
        assertEquals(
            PluginSignatureState.Changed("fingerprint-a", "fingerprint-b"),
            state
        )
    }

    @Test
    fun testUnknownCurrentSignerStillRevokesTrust() {
        val state = PluginSignerApprovals.evaluate(
            descriptor("org.example.plugin", null),
            mapOf("org.example.plugin" to "fingerprint-a")
        )
        assertEquals(PluginSignatureState.Changed("fingerprint-a", null), state)
    }

    @Test
    fun testNoSignerWithoutRecordedApprovalIsUnknown() {
        val state = PluginSignerApprovals.evaluate(
            descriptor("org.example.plugin", null),
            emptyMap()
        )
        assertEquals(PluginSignatureState.Unknown, state)
    }

    @Test
    fun testBaselineRecordsOnlyMissingPackages() {
        val first = descriptor("org.example.first", "fingerprint-a")
        val second = descriptor("org.example.second", "fingerprint-b")

        val updated = PluginSignerApprovals.baseline(
            listOf(first, second),
            mapOf("org.example.first" to "fingerprint-old")
        )

        assertEquals("fingerprint-old", updated["org.example.first"])
        assertEquals("fingerprint-b", updated["org.example.second"])
    }

    @Test
    fun testEncodeLoadRoundTripSkipsMalformedEntries() {
        val approvals = mapOf(
            "org.example.first" to "fingerprint-a",
            "org.example.second" to "fingerprint-b"
        )
        val stored = PluginSignerApprovals.encode(approvals) + setOf(
            "missing-separator",
            "\tfingerprint-only",
            "package-only\t"
        )

        assertEquals(approvals, PluginSignerApprovals.load(stored))
        assertTrue(PluginSignerApprovals.load(stored).size == 2)
    }
}
