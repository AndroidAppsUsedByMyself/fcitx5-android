/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.cloud_pinyin

import java.net.URLEncoder

/**
 * Built-in backends. Disabled by default; the user must opt in from the plugin settings.
 *
 * No official SLA is provided by either endpoint; they may stop working at any time.
 */
enum class CloudPinyinBackend(val key: String) {
    Off("off"),
    Google("google"),
    Baidu("baidu");

    fun requestUrl(pinyin: String): String? {
        val encoded = URLEncoder.encode(pinyin, Charsets.UTF_8.name())
        return when (this) {
            Off -> null
            Google -> "https://www.google.com/inputtools/request?ime=pinyin&text=$encoded"
            Baidu -> "https://olimenew.baidu.com/py?input=$encoded&inputtype=py&resultcoding=utf-8"
        }
    }

    fun parseResponse(body: String): String? = when (this) {
        Off -> null
        Google -> parseGoogle(input = body)
        Baidu -> parseBaidu(input = body)
    }

    companion object {
        fun fromKey(key: String?): CloudPinyinBackend =
            entries.firstOrNull { it.key == key } ?: Off

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

        /**
         * Baidu olime: `{"status":"T","errno":"0","result":[[["候选",...]]]}`.
         * `status` must be `T`, `errno` may be string or number `0`.
         */
        fun parseBaidu(input: String): String? {
            val root = runCatching { JsonReader(input).read() }.getOrNull() as? JsonValue.Obj
                ?: return null
            val status = (root.fields["status"] as? JsonValue.Str)?.value ?: return null
            if (status != "T") return null
            val errno = root.fields["errno"] ?: return null
            val errnoOk = when (errno) {
                is JsonValue.Str -> errno.value == "0"
                is JsonValue.Number -> errno.raw == "0"
                else -> false
            }
            if (!errnoOk) return null
            val result = root.fields["result"] as? JsonValue.Arr ?: return null
            val level1 = result.items.getOrNull(0) as? JsonValue.Arr ?: return null
            val level2 = level1.items.getOrNull(0) as? JsonValue.Arr ?: return null
            return (level2.items.getOrNull(0) as? JsonValue.Str)?.value?.takeIf { it.isNotBlank() }
        }
    }
}
