/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.cloud_pinyin

/**
 * Outcome of an HTTP fetch. Failures carry a reason so contract violations are reported
 * explicitly instead of being silently treated as "no candidate".
 */
sealed interface CloudPinyinFetchResult {
    data class Success(val candidate: String) : CloudPinyinFetchResult
    data class Failure(
        val reason: CloudPinyinFailure,
        val detail: String? = null
    ) : CloudPinyinFetchResult
}

enum class CloudPinyinFailure {
    MalformedUrl,
    InsecureScheme,
    HttpStatus,
    Oversize,
    EmptyBody,
    InvalidUtf8,
    Parse,
    EmptyCandidates,
    Timeout,
    Network,
    ProxyConfigInvalid,
    ProxyCredentialMissing,
    ProxyAuthUnsupported,
    ProxyAuth,
    ProxyTimeout,
    ProxyNetwork
}

sealed interface CloudPinyinParseResult {
    data class Candidate(val value: String) : CloudPinyinParseResult
    data class Failure(
        val reason: CloudPinyinFailure,
        val detail: String? = null
    ) : CloudPinyinParseResult
}

/**
 * A single provider implementation. Backends are created from [ProviderEntry] by
 * [CloudPinyinBackendRegistry].
 */
interface CloudPinyinBackend {
    /**
     * Proxy configured for this provider entry. Defaults to an explicit direct connection.
     */
    val proxy: ProxyConfig get() = ProxyConfig()

    /**
     * Stable identity used for the result cache and per-endpoint backoff. For custom
     * endpoints this includes the configured URL, so it must never be logged.
     */
    val key: String

    /**
     * Non-secret identifier used in logs.
     */
    val id: String

    fun requestUrl(pinyin: String): String?

    fun parse(body: String): CloudPinyinParseResult
}
