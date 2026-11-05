/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core.data

import org.fcitx.fcitx5.android.common.util.PathSafety
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PluginAssetPathTest {

    private val descriptor = PluginDescriptor(
        packageName = "org.example.plugin",
        matchedAction = "org.example.plugin.MANIFEST",
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
    fun testParentTraversalRejected() {
        assertNull(PathSafety.normalizeRelative("../escape"))
        assertNull(PathSafety.normalizeRelative("usr/../../escape"))
    }

    @Test
    fun testAbsolutePathRejected() {
        assertNull(PathSafety.normalizeRelative("/usr/share"))
        assertNull(PathSafety.normalizeRelative("C:\\usr"))
    }

    @Test
    fun testLegalPathsAccepted() {
        assertEquals("usr/share/fcitx5", PathSafety.normalizeRelative("usr/share/fcitx5"))
        assertEquals("usr/share", PathSafety.normalizeRelative("usr/./share"))
    }

    @Test
    fun testEscapingPluginDescriptorRejected() {
        val data = DataDescriptor(
            sha256 = "sha",
            files = mapOf("../escape" to "hash"),
            symlinks = emptyMap()
        )
        assertThrows(DataHierarchy.PathEscape::class.java) {
            DataHierarchy().install(data, FileSource.Plugin(descriptor))
        }
    }

    @Test
    fun testContainedPluginDescriptorAccepted() {
        val data = DataDescriptor(
            sha256 = "sha",
            files = mapOf("usr/share/fcitx5/file.txt" to "hash"),
            symlinks = mapOf("usr/share/fcitx5/link" to "usr/share/fcitx5/file.txt")
        )
        DataHierarchy().install(data, FileSource.Plugin(descriptor))
    }
}
