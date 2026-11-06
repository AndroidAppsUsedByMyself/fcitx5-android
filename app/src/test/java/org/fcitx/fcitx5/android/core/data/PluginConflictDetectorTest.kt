/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.file.Files

class PluginConflictDetectorTest {

    private fun descriptor(packageName: String) = PluginDescriptor(
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
        nativeLibraryDir = ""
    )

    @Test
    fun testAddonConflict() {
        val first = descriptor("org.example.first")
        val registry = PluginConflictRegistry()
        registry.register(FileSource.Plugin(first), PluginClaims(addons = setOf("anthy")))

        val conflict = registry.findConflict(PluginClaims(addons = setOf("anthy")))

        assertEquals(PluginConflictKind.Addon, conflict?.kind)
        assertEquals("anthy", conflict?.resource)
        assertEquals(FileSource.Plugin(first), conflict?.owner)
    }

    @Test
    fun testLibraryConflict() {
        val first = descriptor("org.example.first")
        val registry = PluginConflictRegistry()
        registry.register(
            FileSource.Plugin(first),
            PluginClaims(libraries = setOf("libshared.so"))
        )

        val conflict = registry.findConflict(
            PluginClaims(libraries = setOf("libshared.so"))
        )

        assertEquals(PluginConflictKind.Library, conflict?.kind)
        assertEquals("libshared.so", conflict?.resource)
        assertEquals(FileSource.Plugin(first), conflict?.owner)
    }

    @Test
    fun testDomainConflictWithHost() {
        val registry = PluginConflictRegistry()
        registry.register(
            FileSource.Main,
            PluginClaims(domains = PluginClaimsReader.hostDomains)
        )

        val conflict = registry.findConflict(
            PluginClaims(domains = setOf("fcitx5-chinese-addons"))
        )

        assertEquals(PluginConflictKind.Domain, conflict?.kind)
        assertEquals("fcitx5-chinese-addons", conflict?.resource)
        assertEquals(FileSource.Main, conflict?.owner)
    }

    @Test
    fun testDomainConflictBetweenPlugins() {
        val first = descriptor("org.example.first")
        val registry = PluginConflictRegistry()
        registry.register(
            FileSource.Plugin(first),
            PluginClaims(domains = setOf("example-domain"))
        )

        val conflict = registry.findConflict(
            PluginClaims(domains = setOf("example-domain"))
        )

        assertEquals(PluginConflictKind.Domain, conflict?.kind)
        assertEquals(FileSource.Plugin(first), conflict?.owner)
    }

    @Test
    fun testDistinctClaimsHaveNoConflict() {
        val first = descriptor("org.example.first")
        val registry = PluginConflictRegistry()
        registry.register(
            FileSource.Plugin(first),
            PluginClaims(
                addons = setOf("anthy"),
                libraries = setOf("libanthy.so"),
                domains = setOf("fcitx5-anthy")
            )
        )

        val conflict = registry.findConflict(
            PluginClaims(
                addons = setOf("hangul"),
                libraries = setOf("libhangul.so"),
                domains = setOf("fcitx5-hangul")
            )
        )

        assertNull(conflict)
    }

    @Test
    fun testAddonNamesFromDataFiles() {
        val names = PluginClaimsReader.addonNames(
            listOf(
                "usr/share/fcitx5/addon/anthy.conf",
                "usr/share/fcitx5/addon/cloudpinyin.conf",
                "usr/share/fcitx5/addon/nested/pinyin.conf",
                "usr/share/fcitx5/addon/readme.txt",
                "usr/share/fcitx5/inputmethod/anthy.conf"
            )
        )

        assertEquals(setOf("anthy", "cloudpinyin"), names)
    }

    @Test
    fun testLibraryNamesOnlyIncludesSharedLibraries() {
        val dir = Files.createTempDirectory("plugin-claims").toFile()
        try {
            dir.resolve("libanthy.so").writeText("")
            dir.resolve("anthy.txt").writeText("")
            dir.resolve("sub").mkdir()

            assertEquals(
                setOf("libanthy.so"),
                PluginClaimsReader.libraryNames(dir.absolutePath)
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun testDuplicateDataPathBlocksLaterSource() {
        val first = descriptor("org.example.first")
        val second = descriptor("org.example.second")
        val path = "usr/share/fcitx5/addon/anthy.conf"
        val hierarchy = DataHierarchy()
        hierarchy.install(
            DataDescriptor("first", mapOf(path to "hash1"), emptyMap()),
            FileSource.Plugin(first)
        )

        assertThrows(DataHierarchy.PathConflict::class.java) {
            hierarchy.install(
                DataDescriptor("second", mapOf(path to "hash2"), emptyMap()),
                FileSource.Plugin(second)
            )
        }
    }
}
