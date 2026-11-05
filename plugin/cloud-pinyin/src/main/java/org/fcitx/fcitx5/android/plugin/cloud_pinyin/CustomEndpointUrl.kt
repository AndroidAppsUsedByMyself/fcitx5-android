/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.cloud_pinyin

import java.net.URL

/**
 * UI-side validation for custom endpoint URLs. Fragment suffixes are rejected because
 * the pinyin query parameter is appended after the path and query.
 */
object CustomEndpointUrl {

    fun normalize(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        if ('#' in trimmed) return null
        val url = runCatching { URL(trimmed) }.getOrNull() ?: return null
        if (url.protocol != "https") return null
        if (url.host.isNullOrBlank()) return null
        return trimmed
    }
}
