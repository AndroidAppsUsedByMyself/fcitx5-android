/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core.data

import org.fcitx.fcitx5.android.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class PluginDiscoveryTest {

    private val officialFingerprint = "aa".repeat(32)
    private val downstreamFingerprint = "bb".repeat(32)
    private val unknownFingerprint = "cc".repeat(32)
    private val otherFingerprint = "dd".repeat(32)

    @Test
    fun testOfficialFingerprintIsOfficial() {
        val policy = PluginSignaturePolicy(officialSignatures = setOf(officialFingerprint))
        assertEquals(PluginOrigin.Official, policy.originOf(setOf(officialFingerprint)))
    }

    @Test
    fun testDownstreamFingerprintIsDownstream() {
        val policy = PluginSignaturePolicy(downstreamSignatures = setOf(downstreamFingerprint))
        assertEquals(PluginOrigin.Downstream, policy.originOf(setOf(downstreamFingerprint)))
    }

    @Test
    fun testUnknownFingerprintIsThirdParty() {
        val policy = PluginSignaturePolicy(
            officialSignatures = setOf(officialFingerprint),
            downstreamSignatures = setOf(downstreamFingerprint)
        )
        assertEquals(PluginOrigin.ThirdParty, policy.originOf(setOf(unknownFingerprint)))
    }

    @Test
    fun testEmptyTrustSetIsThirdParty() {
        assertEquals(
            PluginOrigin.ThirdParty,
            PluginSignaturePolicy().originOf(setOf(officialFingerprint))
        )
    }

    @Test
    fun testConfiguredUpstreamSignerIsOfficial() {
        assertEquals(
            PluginOrigin.Official,
            PluginTrust.policy().originOf(setOf(PluginTrust.UPSTREAM_RELEASE_SHA256))
        )
    }

    @Test
    fun testConfiguredDebugSignerFollowsBuildType() {
        val expected = if (BuildConfig.DEBUG) {
            PluginOrigin.Downstream
        } else {
            PluginOrigin.ThirdParty
        }
        assertEquals(
            expected,
            PluginTrust.policy().originOf(setOf(PluginTrust.ANDROID_DEBUG_SHA256))
        )
    }

    @Test
    fun testConfiguredPolicyRejectsUnknownFingerprint() {
        assertEquals(
            PluginOrigin.ThirdParty,
            PluginTrust.policy().originOf(setOf(unknownFingerprint))
        )
    }

    @Test
    fun testAnyTrustedSignerWins() {
        val policy = PluginSignaturePolicy(officialSignatures = setOf(officialFingerprint))
        assertEquals(
            PluginOrigin.Official,
            policy.originOf(setOf(unknownFingerprint, otherFingerprint, officialFingerprint))
        )
    }

    @Test
    fun testResolvePluginMatchesDeduplicatesByPackageName() {
        val hits = mapOf(
            "org.example.plugin.rime" to setOf(
                PluginManifestActions.OFFICIAL_RELEASE,
                PluginManifestActions.host
            ),
            "org.example.plugin.anthy" to setOf(PluginManifestActions.OFFICIAL_DEBUG)
        )
        val matches = resolvePluginMatches(hits)
        assertEquals(2, matches.size)
        assertEquals(
            listOf("org.example.plugin.anthy", "org.example.plugin.rime"),
            matches.map { it.packageName }
        )
    }

    @Test
    fun testHostActionIsPreferred() {
        val matches = resolvePluginMatches(
            mapOf(
                "org.example.plugin.rime" to setOf(
                    PluginManifestActions.OFFICIAL_RELEASE,
                    PluginManifestActions.host
                )
            )
        )
        assertEquals(PluginManifestActions.host, matches.single().matchedAction)
    }

    @Test
    fun testUnknownActionIsNotBuiltForHost() {
        val matches = resolvePluginMatches(
            mapOf("org.example.plugin.rime" to setOf("org.example.unknown.MANIFEST"))
        )
        assertEquals("org.example.unknown.MANIFEST", matches.single().matchedAction)
    }
}
