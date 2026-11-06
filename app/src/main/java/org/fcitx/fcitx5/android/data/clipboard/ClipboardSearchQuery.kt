/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.clipboard

/**
 * Clipboard history is searched with SQLite `LIKE`.
 *
 * `!` is the escape character in [org.fcitx.fcitx5.android.data.clipboard.db.ClipboardDao.searchEntries],
 * so user input must be escaped to match literally instead of acting as a wildcard.
 */
object ClipboardSearchQuery {

    fun likePattern(query: String): String {
        val escaped = query
            .replace("!", "!!")
            .replace("%", "!%")
            .replace("_", "!_")
        return "%$escaped%"
    }
}
