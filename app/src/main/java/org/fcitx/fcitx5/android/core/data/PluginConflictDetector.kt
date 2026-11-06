/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core.data

import java.io.File

/**
 * Namespaces that are shared by all merged plugins and resolved first-wins by fcitx.
 */
enum class PluginConflictKind {
    /** fcitx addon name, i.e. the stem of `usr/share/fcitx5/addon/<name>.conf`. */
    Addon,

    /** Shared library file name, resolved through `FCITX_ADDON_DIRS` in order. */
    Library,

    /** gettext domain, registered once by fcitx (later registrations are ignored). */
    Domain
}

/**
 * Resources requested by one data source (the host or a plugin).
 */
data class PluginClaims(
    val addons: Set<String> = emptySet(),
    val libraries: Set<String> = emptySet(),
    val domains: Set<String> = emptySet()
)

data class PluginConflict(
    val kind: PluginConflictKind,
    val resource: String,
    val owner: FileSource
)

/**
 * Tracks resources claimed by data sources that were already merged.
 *
 * The host is registered first, then plugins in merge order. A plugin whose claims collide
 * with a registered owner must not be merged: fcitx would load the first owner and silently
 * ignore or shadow the later one.
 */
class PluginConflictRegistry {

    private val addons = mutableMapOf<String, FileSource>()
    private val libraries = mutableMapOf<String, FileSource>()
    private val domains = mutableMapOf<String, FileSource>()

    fun register(owner: FileSource, claims: PluginClaims) {
        claims.addons.forEach { addons.putIfAbsent(it, owner) }
        claims.libraries.forEach { libraries.putIfAbsent(it, owner) }
        claims.domains.forEach { domains.putIfAbsent(it, owner) }
    }

    fun findConflict(claims: PluginClaims): PluginConflict? {
        claims.addons.forEach { name ->
            addons[name]?.let {
                return PluginConflict(PluginConflictKind.Addon, name, it)
            }
        }
        claims.libraries.forEach { name ->
            libraries[name]?.let {
                return PluginConflict(PluginConflictKind.Library, name, it)
            }
        }
        claims.domains.forEach { domain ->
            domains[domain]?.let {
                return PluginConflict(PluginConflictKind.Domain, domain, it)
            }
        }
        return null
    }
}

object PluginClaimsReader {

    private const val ADDON_DIR = "usr/share/fcitx5/addon/"
    private const val ADDON_EXTENSION = ".conf"

    /**
     * gettext domains registered by the host itself.
     *
     * Keep in sync with the `registerDomain()` calls in `startupFcitx()`
     * (app/src/main/cpp/native-lib.cpp).
     */
    val hostDomains: Set<String> = setOf(
        "fcitx5",
        "fcitx5-lua",
        "fcitx5-chinese-addons",
        "fcitx5-android"
    )

    /**
     * fcitx uses the `.conf` file name as the addon name, so data paths are authoritative.
     */
    fun addonNames(dataFiles: Collection<String>): Set<String> =
        dataFiles.mapNotNull { path ->
            if (!path.startsWith(ADDON_DIR) || !path.endsWith(ADDON_EXTENSION)) {
                return@mapNotNull null
            }
            val name = path.removePrefix(ADDON_DIR).removeSuffix(ADDON_EXTENSION)
            name.takeIf { it.isNotEmpty() && '/' !in it }
        }.toSet()

    /**
     * Shared library names are resolved in `FCITX_ADDON_DIRS` order, i.e. the host first
     * and then plugins in merge order.
     */
    fun libraryNames(nativeLibraryDir: String): Set<String> {
        if (nativeLibraryDir.isBlank()) return emptySet()
        return File(nativeLibraryDir).listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.name.endsWith(".so") }
            ?.map { it.name }
            ?.toSet()
            .orEmpty()
    }
}
