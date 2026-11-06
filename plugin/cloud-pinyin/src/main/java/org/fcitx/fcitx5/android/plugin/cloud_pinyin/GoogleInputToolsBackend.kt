/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.cloud_pinyin

import java.net.URLEncoder

/**
 * Shared implementation for Google inputtools endpoints (google.com and google.cn).
 */
open class GoogleInputToolsBackend(
    override val id: String,
    private val baseUrl: String,
    override val proxy: ProxyConfig = ProxyConfig()
) : CloudPinyinBackend {

    override val key: String get() = "$id|${proxy.identity}"

    override fun requestUrl(pinyin: String): String {
        val encoded = URLEncoder.encode(pinyin, Charsets.UTF_8.name())
        return baseUrl + encoded
    }

    override fun parse(body: String): CloudPinyinParseResult {
        val candidate = parseGoogle(body)
            ?: return CloudPinyinParseResult.Failure(CloudPinyinFailure.Parse)
        return CloudPinyinParseResult.Candidate(candidate)
    }

    companion object {
        /**
         * Google inputtools: `["SUCCESS", [["pinyin", ["候选", ...], ...]]]`.
         * Only `SUCCESS` is accepted; anything else is a failure.
         */
        fun parseGoogle(input: String): String? {
            val root = runCatching { JsonReader(input).read() }.getOrNull() as? JsonValue.Arr
                ?: return null
            val status = (root.items.getOrNull(0) as? JsonValue.Str)?.value ?: return null
            if (status != "SUCCESS") return null
            val payload = root.items.getOrNull(1) as? JsonValue.Arr ?: return null
            val group = payload.items.getOrNull(0) as? JsonValue.Arr ?: return null
            val candidates = group.items.getOrNull(1) as? JsonValue.Arr ?: return null
            return (candidates.items.getOrNull(0) as? JsonValue.Str)?.value?.takeIf { it.isNotBlank() }
        }
    }
}
