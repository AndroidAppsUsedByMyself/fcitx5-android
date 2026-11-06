/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.cloud_pinyin

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Line based encoding of an ordered provider list.
 *
 * Each line has up to seven tab-separated fields: provider id, custom URL, proxy kind,
 * proxy host, proxy port, proxy username and credential id. Older two-field lines are
 * still accepted and decode to a direct provider. Unknown ids are dropped so a corrupt
 * or stale preference never breaks lookups.
 *
 * Password values never appear here; only [ProxyConfig.credentialId] does.
 */
object ProviderListCodec {

    private const val FIELD_COUNT = 7

    fun encode(entries: List<ProviderEntry>): String =
        entries.joinToString("\n") { entry ->
            val customUrl = if (entry.provider == CloudPinyinProvider.Custom) {
                encodeField(entry.customUrl.orEmpty())
            } else {
                ""
            }
            val proxy = entry.proxy
            val proxyFields = if (proxy.enabled) {
                listOf(
                    proxy.kind.id,
                    encodeField(proxy.host),
                    proxy.port.takeIf { it > 0 }?.toString().orEmpty(),
                    encodeField(proxy.username.orEmpty()),
                    proxy.credentialId.orEmpty()
                )
            } else {
                listOf("", "", "", "", "")
            }
            (listOf(entry.provider.id, customUrl) + proxyFields)
                .joinToString("\t")
        }

    fun decode(raw: String?): List<ProviderEntry> {
        if (raw == null) return emptyList()
        return raw.split('\n').mapNotNull { line ->
            val fields = line.split('\t', limit = FIELD_COUNT)
            val provider = CloudPinyinProvider.fromId(fields[0].trim())
                ?: return@mapNotNull null
            val url = fields.getOrNull(1)
                ?.let(::decodeField)
                ?.takeIf { it.isNotBlank() }
            if (provider == CloudPinyinProvider.Custom && url == null) {
                null
            } else {
                ProviderEntry(provider, url, decodeProxy(fields))
            }
        }
    }

    private fun decodeProxy(fields: List<String>): ProxyConfig {
        if (fields.size < 3) return ProxyConfig()
        val kind = ProxyKind.fromId(fields[2].trim()) ?: return ProxyConfig()
        if (kind == ProxyKind.None) return ProxyConfig()
        val host = decodeField(fields.getOrNull(3).orEmpty())
        val port = fields.getOrNull(4)?.trim()?.toIntOrNull() ?: 0
        val username = decodeField(fields.getOrNull(5).orEmpty())
            .takeIf { it.isNotBlank() }
        val credentialId = fields.getOrNull(6)?.trim()?.takeIf { it.isNotBlank() }
        return ProxyConfig(kind, host, port, username, credentialId)
    }

    private fun encodeField(value: String): String =
        URLEncoder.encode(value, "UTF-8")

    private fun decodeField(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault("")
}
