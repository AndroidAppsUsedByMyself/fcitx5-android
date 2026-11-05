/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core.data

import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.common.plugin.PluginProvides
import org.fcitx.fcitx5.android.common.plugin.PluginService

/**
 * Where a plugin APK comes from.
 *
 * Origin is determined solely by the APK signing certificate, never by package name
 * or metadata declared by the plugin itself.
 */
enum class PluginOrigin {
    /**
     * Signed by a certificate listed in [PluginTrust.officialSignatures].
     */
    Official,

    /**
     * Signed by a certificate listed in [PluginTrust.downstreamSignatures].
     */
    Downstream,

    /**
     * Not signed by any trusted certificate, including the case of an empty trust set.
     */
    ThirdParty
}

/**
 * Trusted plugin signers, expressed as lowercase hex SHA-256 certificate fingerprints.
 */
object PluginTrust {
    /**
     * Release certificate of the upstream project, taken from the F-Droid metadata
     * (`AllowedAPKSigningKeys` in `app/org.fcitx.fcitx5.android.yml`). No other upstream
     * certificate is recorded in this repository.
     */
    const val UPSTREAM_RELEASE_SHA256 =
        "e4db1e9edff13629d07de4bbf8165fe9bd8557ab55092672da8e40dbe484ecd7"

    /**
     * The public Android debug certificate (`CN=Android Debug`, also used by the debug
     * APKs in this workspace per `apksigner verify --print-certs`). It is shared by every
     * Android debug build, so it must only be trusted in debug builds.
     */
    const val ANDROID_DEBUG_SHA256 =
        "fa269cd524ba67b1a843bb8c785cbc254e7c532ded734da2456d2686d96747c6"

    @Volatile
    var officialSignatures: Set<String> = setOf(UPSTREAM_RELEASE_SHA256)

    @Volatile
    var downstreamSignatures: Set<String> =
        if (BuildConfig.DEBUG) setOf(ANDROID_DEBUG_SHA256) else emptySet()

    fun policy(): PluginSignaturePolicy =
        PluginSignaturePolicy(officialSignatures, downstreamSignatures)
}

data class PluginSignaturePolicy(
    val officialSignatures: Set<String> = emptySet(),
    val downstreamSignatures: Set<String> = emptySet()
) {
    fun originOf(fingerprints: Set<String>): PluginOrigin = when {
        fingerprints.any { it in officialSignatures } -> PluginOrigin.Official
        fingerprints.any { it in downstreamSignatures } -> PluginOrigin.Downstream
        else -> PluginOrigin.ThirdParty
    }
}

/**
 * Manifest actions that can advertise a plugin APK.
 *
 * Official actions are listed literally; [host] is the action of the running variant.
 * [all] is deduplicated because a downstream build may use the official application id.
 */
object PluginManifestActions {
    const val OFFICIAL_RELEASE = "org.fcitx.fcitx5.android.plugin.MANIFEST"
    const val OFFICIAL_DEBUG = "org.fcitx.fcitx5.android.debug.plugin.MANIFEST"

    val host: String = "${BuildConfig.APPLICATION_ID}.plugin.MANIFEST"

    val all: List<String> = listOf(OFFICIAL_RELEASE, OFFICIAL_DEBUG, host).distinct()

    val hostActions: Set<String> = setOf(host)
}

data class PluginMatch(
    val packageName: String,
    val matchedAction: String
)

/**
 * Collapse per-action package hits into one entry per package name.
 *
 * A package that advertises several known actions is counted once. The host action is
 * preferred as [PluginMatch.matchedAction]; otherwise the lexicographically first action is used.
 */
fun resolvePluginMatches(hits: Map<String, Collection<String>>): List<PluginMatch> =
    hits.map { (packageName, actions) ->
        val matchedAction = actions.firstOrNull { it in PluginManifestActions.hostActions }
            ?: actions.sorted().first()
        PluginMatch(packageName, matchedAction)
    }.sortedBy { it.packageName }

/**
 * Metadata of a plugin, at `res/xml/plugin.xml`
 */
data class PluginDescriptor(
    /**
     * Package name of the plugin APK; the identity key used for assets and IPC.
     */
    val packageName: String,
    /**
     * Known manifest action that advertised this plugin.
     */
    val matchedAction: String,
    /**
     * Origin determined from the APK signing certificate.
     */
    val origin: PluginOrigin,
    /**
     * For future incompatible updates
     */
    val apiVersion: String,
    /**
     * May provide gettext domain
     */
    val domain: String?,
    /**
     * Can use string resource, e.g. `@string/description`
     */
    val description: String,
    /**
     * Declared addons, native libraries, and data roots, if any.
     */
    val provides: PluginProvides?,
    /**
     * Declared IPC service, if any.
     */
    val service: PluginService?,
    /**
     * Whether the plugin exposes an IPC service, including the legacy `hasService` hint.
     */
    val hasService: Boolean,
    val versionName: String,
    val nativeLibraryDir: String,
    /**
     * Lowercase hex SHA-256 of the APK signer certificate, or `null` if unavailable.
     */
    val signerFingerprint: String? = null,
    /**
     * Whether the plugin APK itself requests `android.permission.INTERNET`.
     */
    val requestsInternet: Boolean = false,
    /**
     * Optional settings activity class declared by the plugin.
     */
    val settingsActivity: String? = null
) {
    val builtForHost: Boolean = matchedAction in PluginManifestActions.hostActions

    val declaresIpc: Boolean
        get() = service != null || hasService

    companion object {
        const val pluginAPI = "0.1"
    }
}
