/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsActivityTargetTest {

    @Test
    fun testRelativeClassName() {
        assertEquals(
            "org.example.plugin.SettingsActivity",
            resolveSettingsActivityClass(
                "org.example.plugin",
                ".SettingsActivity"
            )
        )
    }

    @Test
    fun testAbsoluteClassName() {
        assertEquals(
            "org.example.other.SettingsActivity",
            resolveSettingsActivityClass(
                "org.example.plugin",
                "org.example.other.SettingsActivity"
            )
        )
    }
}
