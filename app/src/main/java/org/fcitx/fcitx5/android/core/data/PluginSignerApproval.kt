/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core.data

/**
 * How the installed signer compares with the fingerprint recorded for the package.
 */
sealed interface PluginSignatureState {

    /** No signer fingerprint is available for the installed APK. */
    data object Unknown : PluginSignatureState

    /** No fingerprint was recorded for this package yet. */
    data object New : PluginSignatureState

    /** The installed signer matches the recorded fingerprint. */
    data object Approved : PluginSignatureState

    /**
     * The package was recorded with a different signer. Previous trust is revoked and
     * the plugin stays disabled until the user approves the current signer.
     */
    data class Changed(
        val previousFingerprint: String,
        val currentFingerprint: String?
    ) : PluginSignatureState
}

/**
 * Persisted signer approvals, keyed by package name.
 *
 * A new signer for a known package must never inherit the previous approval: reusing
 * [PluginTrust] fingerprints alone cannot detect this, because the package name stays the
 * same while the certificate changes.
 */
object PluginSignerApprovals {

    private const val SEPARATOR = '\t'

    fun evaluate(
        descriptor: PluginDescriptor,
        approvals: Map<String, String>
    ): PluginSignatureState {
        val current = descriptor.signerFingerprint
        val previous = approvals[descriptor.packageName]
        return when {
            previous == null -> if (current == null) {
                PluginSignatureState.Unknown
            } else {
                PluginSignatureState.New
            }

            current == null -> PluginSignatureState.Changed(previous, null)
            current == previous -> PluginSignatureState.Approved
            else -> PluginSignatureState.Changed(previous, current)
        }
    }

    /**
     * Record a fingerprint for newly discovered packages without touching existing entries.
     */
    fun baseline(
        descriptors: Collection<PluginDescriptor>,
        approvals: Map<String, String>
    ): Map<String, String> {
        val updated = approvals.toMutableMap()
        descriptors.forEach { descriptor ->
            val fingerprint = descriptor.signerFingerprint
            if (fingerprint != null && descriptor.packageName !in updated) {
                updated[descriptor.packageName] = fingerprint
            }
        }
        return updated
    }

    fun load(stored: Set<String>): Map<String, String> =
        stored.mapNotNull { entry ->
            val index = entry.indexOf(SEPARATOR)
            if (index <= 0 || index == entry.lastIndex) {
                return@mapNotNull null
            }
            entry.substring(0, index) to entry.substring(index + 1)
        }.toMap()

    fun encode(approvals: Map<String, String>): Set<String> =
        approvals.mapTo(mutableSetOf()) { (packageName, fingerprint) ->
            "$packageName$SEPARATOR$fingerprint"
        }
}
