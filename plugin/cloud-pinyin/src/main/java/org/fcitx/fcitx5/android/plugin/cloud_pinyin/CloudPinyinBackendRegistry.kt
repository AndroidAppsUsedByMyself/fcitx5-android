/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.cloud_pinyin

/**
 * Maps configured provider entries to backend implementations.
 */
object CloudPinyinBackendRegistry {

    fun create(entry: ProviderEntry): CloudPinyinBackend = when (entry.provider) {
        CloudPinyinProvider.GoogleCn -> GoogleCnBackend(entry.proxy)
        CloudPinyinProvider.Google -> GoogleBackend(entry.proxy)
        CloudPinyinProvider.Baidu -> BaiduBackend(entry.proxy)
        // Keep invalid URLs in the pipeline so they fail explicitly and are logged.
        CloudPinyinProvider.Custom -> CustomEndpointBackend(entry.customUrl.orEmpty(), entry.proxy)
    }

    fun createAll(entries: List<ProviderEntry>): List<CloudPinyinBackend> =
        entries.map(::create)
}
