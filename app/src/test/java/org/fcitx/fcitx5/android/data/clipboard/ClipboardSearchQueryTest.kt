/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.clipboard

import org.junit.Assert.assertEquals
import org.junit.Test

class ClipboardSearchQueryTest {

    @Test
    fun testPlainQuery() {
        assertEquals("%hello%", ClipboardSearchQuery.likePattern("hello"))
    }

    @Test
    fun testPercentIsEscaped() {
        assertEquals("%100!%%", ClipboardSearchQuery.likePattern("100%"))
    }

    @Test
    fun testUnderscoreIsEscaped() {
        assertEquals("%a!_b%", ClipboardSearchQuery.likePattern("a_b"))
    }

    @Test
    fun testEscapeCharacterIsEscaped() {
        assertEquals("%!!x%", ClipboardSearchQuery.likePattern("!x"))
    }

    @Test
    fun testEmptyQueryMatchesEverything() {
        assertEquals("%%", ClipboardSearchQuery.likePattern(""))
    }
}
