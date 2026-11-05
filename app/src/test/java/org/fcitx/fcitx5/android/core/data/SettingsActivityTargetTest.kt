/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsActivityTargetTest {

    private val applicationId = "org.example.plugin.debug"
    private val namespace = "org.example.plugin"

    @Test
    fun testRelativeNameUsesNamespaceComponent() {
        assertEquals(
            "$namespace.SettingsActivity",
            resolveSettingsActivityClass(
                applicationId,
                ".SettingsActivity",
                listOf("$namespace.SettingsActivity")
            )
        )
    }

    @Test
    fun testRelativeNameFallsBackToApplicationIdComponent() {
        assertEquals(
            "$applicationId.SettingsActivity",
            resolveSettingsActivityClass(
                applicationId,
                ".SettingsActivity",
                listOf("$applicationId.SettingsActivity")
            )
        )
    }

    @Test
    fun testRelativeNameWithoutComponentListUsesLegacyExpansion() {
        assertEquals(
            "$applicationId.SettingsActivity",
            resolveSettingsActivityClass(applicationId, ".SettingsActivity", emptyList())
        )
    }

    @Test
    fun testFullyQualifiedNameIsReturned() {
        assertEquals(
            "$namespace.SettingsActivity",
            resolveSettingsActivityClass(
                applicationId,
                "$namespace.SettingsActivity",
                listOf("$namespace.SettingsActivity")
            )
        )
    }

    @Test
    fun testMissingFullyQualifiedNameIsRejected() {
        assertNull(
            resolveSettingsActivityClass(
                applicationId,
                "$namespace.SettingsActivity",
                listOf("$namespace.OtherActivity")
            )
        )
    }

    @Test
    fun testAmbiguousRelativeNameIsRejected() {
        assertNull(
            resolveSettingsActivityClass(
                applicationId,
                ".SettingsActivity",
                listOf(
                    "org.example.a.SettingsActivity",
                    "org.example.b.SettingsActivity"
                )
            )
        )
    }

    @Test
    fun testShortNameIsResolvedBySuffix() {
        assertEquals(
            "$namespace.SettingsActivity",
            resolveSettingsActivityClass(
                applicationId,
                "SettingsActivity",
                listOf("$namespace.SettingsActivity")
            )
        )
    }
}
