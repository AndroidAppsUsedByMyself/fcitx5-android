/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.common.util

import java.io.File

/**
 * Helpers for validating paths that originate from untrusted plugin data.
 */
object PathSafety {

    /**
     * Normalize a relative path and return `null` when it is not safely contained.
     *
     * Rejected inputs are empty paths, absolute paths, Windows-style separators,
     * and paths that climb above their root with `..`.
     */
    fun normalizeRelative(path: String): String? {
        if (path.isBlank() || path.startsWith('/') || path.contains('\\')) return null
        val segments = mutableListOf<String>()
        for (segment in path.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> {
                    if (segments.isEmpty()) return null
                    segments.removeAt(segments.lastIndex)
                }
                else -> segments.add(segment)
            }
        }
        return segments.takeIf { it.isNotEmpty() }?.joinToString("/")
    }

    /**
     * Whether [child] resolves inside [root], following existing symbolic links.
     */
    fun isWithin(root: File, child: File): Boolean {
        val rootPath = root.canonicalPath
        val childPath = child.canonicalPath
        return childPath == rootPath || childPath.startsWith("$rootPath${File.separator}")
    }
}
