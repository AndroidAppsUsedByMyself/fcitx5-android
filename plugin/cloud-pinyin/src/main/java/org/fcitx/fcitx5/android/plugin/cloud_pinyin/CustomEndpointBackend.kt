/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.cloud_pinyin

import java.net.URLEncoder

/**
 * A user supplied endpoint.
 *
 * Contract: HTTPS GET, 2xx, strict UTF-8, at most 4KB, and a JSON body shaped like
 * `{"candidates":["候选1","候选2"]}`. The pinyin is appended as query parameter `q`.
 */
class CustomEndpointBackend(
    private val baseUrl: String,
    override val proxy: ProxyConfig = ProxyConfig()
) : CloudPinyinBackend {

    override val id: String get() = CloudPinyinProvider.Custom.id

    override val key: String get() = "$id:$baseUrl|${proxy.identity}"

    override fun requestUrl(pinyin: String): String {
        val encoded = URLEncoder.encode(pinyin, Charsets.UTF_8.name())
        val separator = when {
            baseUrl.endsWith("?") -> ""
            '?' in baseUrl -> "&"
            else -> "?"
        }
        return "$baseUrl${separator}q=$encoded"
    }

    override fun parse(body: String): CloudPinyinParseResult =
        CustomCandidatesParser.parse(body)
}
