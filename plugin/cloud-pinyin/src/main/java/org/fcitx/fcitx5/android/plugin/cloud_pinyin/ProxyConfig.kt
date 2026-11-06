/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.cloud_pinyin

/**
 * Per-provider proxy selection.
 *
 * [username] and [credentialId] are part of the persisted model so a future SOCKS5
 * authentication implementation can reuse the same storage format. Only HTTP Basic
 * authentication is implemented today.
 */
enum class ProxyKind(val id: String) {
    None("none"),
    Http("http"),
    Socks5("socks5");

    companion object {
        fun fromId(value: String?): ProxyKind? =
            entries.firstOrNull { it.id == value }
    }
}

data class ProxyConfig(
    val kind: ProxyKind = ProxyKind.None,
    val host: String = "",
    val port: Int = 0,
    val username: String? = null,
    val credentialId: String? = null
) {
    val enabled: Boolean get() = kind != ProxyKind.None

    /** True when authentication fields are present, even if incomplete. */
    val hasAuthFields: Boolean
        get() = !username.isNullOrBlank() || !credentialId.isNullOrBlank()

    /** Structural validation only; credentials are checked when a request is made. */
    val isValid: Boolean
        get() = !enabled || (host.isNotBlank() && port in 1..65535)

    /**
     * Stable identity used in cache and backoff keys. Never contains a password.
     */
    val identity: String
        get() = if (!enabled) {
            "direct"
        } else {
            listOf(kind.id, host.trim(), port.toString(), credentialId.orEmpty())
                .joinToString(":")
        }

    /** Non-secret label for logs. */
    val logLabel: String
        get() = if (!enabled) "direct" else "${kind.id}://${host.trim()}:$port"
}
