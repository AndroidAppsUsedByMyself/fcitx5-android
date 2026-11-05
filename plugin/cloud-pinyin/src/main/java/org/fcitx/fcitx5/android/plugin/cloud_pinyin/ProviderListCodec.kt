/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.cloud_pinyin

/**
 * Line based encoding of an ordered provider list. Unknown ids are dropped so a corrupt
 * or stale preference never breaks lookups.
 */
object ProviderListCodec {

    fun encode(entries: List<ProviderEntry>): String =
        entries.joinToString("\n") { it.provider.id }

    fun decode(raw: String?): List<ProviderEntry> {
        if (raw == null) return emptyList()
        return raw.split('\n')
            .mapNotNull { CloudPinyinProvider.fromId(it.trim()) }
            .map(::ProviderEntry)
    }
}
