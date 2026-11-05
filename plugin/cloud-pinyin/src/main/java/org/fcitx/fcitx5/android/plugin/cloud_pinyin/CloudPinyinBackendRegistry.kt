/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.cloud_pinyin

/**
 * Maps configured provider entries to backend implementations.
 */
object CloudPinyinBackendRegistry {

    fun create(entry: ProviderEntry): CloudPinyinBackend? = when (entry.provider) {
        CloudPinyinProvider.GoogleCn -> GoogleCnBackend()
        CloudPinyinProvider.Google -> GoogleBackend()
        CloudPinyinProvider.Baidu -> BaiduBackend()
    }

    fun createAll(entries: List<ProviderEntry>): List<CloudPinyinBackend> =
        entries.mapNotNull(::create)
}
