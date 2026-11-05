/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.cloud_pinyin

/**
 * Provider kinds known to the plugin. The ordered provider list is built from these.
 */
enum class CloudPinyinProvider(
    val id: String,
    val labelRes: Int,
    val endpointLabel: String?
) {
    GoogleCn("googlecn", R.string.provider_google_cn, "www.google.cn"),
    Google("google", R.string.provider_google, "www.google.com"),
    Baidu("baidu", R.string.provider_baidu, "olimenew.baidu.com"),
    Custom("custom", R.string.provider_custom, null);

    companion object {
        fun fromId(id: String?): CloudPinyinProvider? =
            entries.firstOrNull { it.id == id }
    }
}

data class ProviderEntry(
    val provider: CloudPinyinProvider,
    val customUrl: String? = null
)
