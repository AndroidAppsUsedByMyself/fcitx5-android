/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.cloud_pinyin

import java.net.URLEncoder

/**
 * Baidu olime endpoint.
 */
class BaiduBackend : CloudPinyinBackend {

    override val id: String get() = CloudPinyinProvider.Baidu.id

    override val key: String get() = id

    override fun requestUrl(pinyin: String): String {
        val encoded = URLEncoder.encode(pinyin, Charsets.UTF_8.name())
        return "https://olimenew.baidu.com/py?input=$encoded&inputtype=py&resultcoding=utf-8"
    }

    override fun parse(body: String): CloudPinyinParseResult {
        val candidate = parseBaidu(body)
            ?: return CloudPinyinParseResult.Failure(CloudPinyinFailure.Parse)
        return CloudPinyinParseResult.Candidate(candidate)
    }

    companion object {
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
