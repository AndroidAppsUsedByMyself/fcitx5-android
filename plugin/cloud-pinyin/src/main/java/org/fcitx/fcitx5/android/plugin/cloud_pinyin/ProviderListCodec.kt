/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.cloud_pinyin

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Line based encoding of an ordered provider list. Custom entries carry their URL in a
 * second URL-encoded field. Unknown ids are dropped so a corrupt or stale preference
 * never breaks lookups.
 */
object ProviderListCodec {

    fun encode(entries: List<ProviderEntry>): String =
        entries.joinToString("\n") { entry ->
            if (entry.provider == CloudPinyinProvider.Custom && entry.customUrl != null) {
                entry.provider.id + "\t" + URLEncoder.encode(entry.customUrl, "UTF-8")
            } else {
                entry.provider.id
            }
        }

    fun decode(raw: String?): List<ProviderEntry> {
        if (raw == null) return emptyList()
        return raw.split('\n').mapNotNull { line ->
            val fields = line.split('\t', limit = 2)
            val provider = CloudPinyinProvider.fromId(fields[0].trim())
                ?: return@mapNotNull null
            val url = fields.getOrNull(1)?.let {
                runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull()
            }
            if (provider == CloudPinyinProvider.Custom && url.isNullOrBlank()) {
                null
            } else {
                ProviderEntry(provider, url)
            }
        }
    }
}
