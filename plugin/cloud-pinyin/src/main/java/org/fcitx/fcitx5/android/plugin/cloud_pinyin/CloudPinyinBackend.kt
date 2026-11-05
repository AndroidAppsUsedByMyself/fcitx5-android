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
        Google -> {
            if (!body.contains("\"SUCCESS\"")) return null
            firstCjkCandidate(body)
        }

        Baidu -> {
            if (!STATUS_OK.containsMatchIn(body) || !ERRNO_OK.containsMatchIn(body)) return null
            firstCjkCandidate(body)
        }
    }

    companion object {
        private val STATUS_OK = Regex("\"status\"\\s*:\\s*\"T\"")
        private val ERRNO_OK = Regex("\"errno\"\\s*:\\s*\"0\"")
        private val QUOTED = Regex("\"((?:\\\\.|[^\"\\\\])*)\"")
        private val CJK = Regex("[\\u3400-\\u9fff]")

        fun fromKey(key: String?): CloudPinyinBackend =
            entries.firstOrNull { it.key == key } ?: Off

        private fun firstCjkCandidate(body: String): String? =
            QUOTED.findAll(body)
                .map { unescape(it.groupValues[1]) }
                .firstOrNull { CJK.containsMatchIn(it) && it.isNotBlank() }

        private fun unescape(value: String): String =
            value.replace("\\\"", "\"").replace("\\\\", "\\")
    }
}
