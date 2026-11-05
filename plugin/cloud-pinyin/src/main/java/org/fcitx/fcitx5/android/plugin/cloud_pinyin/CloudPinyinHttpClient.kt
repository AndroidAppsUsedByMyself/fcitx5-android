/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.cloud_pinyin

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.ConcurrentHashMap

sealed interface EndpointCheck {
    data class Valid(val url: URL) : EndpointCheck
    data class Invalid(
        val reason: CloudPinyinFailure,
        val detail: String? = null
    ) : EndpointCheck
}

/**
 * Validates a request URL before any network access: HTTPS only, parseable, with a host.
 */
fun checkEndpoint(urlString: String?): EndpointCheck {
    if (urlString.isNullOrBlank()) {
        return EndpointCheck.Invalid(CloudPinyinFailure.MalformedUrl)
    }
    val url = runCatching { URL(urlString) }.getOrNull()
        ?: return EndpointCheck.Invalid(CloudPinyinFailure.MalformedUrl)
    if (url.protocol != "https") {
        return EndpointCheck.Invalid(CloudPinyinFailure.InsecureScheme, url.protocol)
    }
    if (url.host.isNullOrBlank()) {
        return EndpointCheck.Invalid(CloudPinyinFailure.MalformedUrl, "missing host")
    }
    return EndpointCheck.Valid(url)
}

sealed interface BodyReadResult {
    data class Content(val bytes: ByteArray) : BodyReadResult
    data object Empty : BodyReadResult
    data object Oversize : BodyReadResult
    data object Timeout : BodyReadResult
}

/**
 * Strict UTF-8 decoding. Malformed or unmappable bytes are a failure, never replacement
 * characters.
 */
fun decodeUtf8(bytes: ByteArray): String? = runCatching {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
}.getOrNull()

/**
 * HTTPS-only fetcher with strict timeouts and a hard response size limit.
 */
class CloudPinyinHttpClient(
    private val maxBytes: Int = 4 * 1024,
    private val maxAttemptMillis: Long = 700L
) {

    private val connections = ConcurrentHashMap<Long, HttpURLConnection>()

    fun fetch(
        backend: CloudPinyinBackend,
        pinyin: String,
        requestId: Long,
        timeoutMillis: Long = maxAttemptMillis
    ): CloudPinyinFetchResult {
        val started = System.currentTimeMillis()
        val endpoint = when (val check = checkEndpoint(backend.requestUrl(pinyin))) {
            is EndpointCheck.Invalid ->
                return fail(backend, requestId, started, check.reason, check.detail)

            is EndpointCheck.Valid -> check.url
        }
        val timeout = timeoutMillis.coerceIn(1L, maxAttemptMillis)
        var connection: HttpURLConnection? = null
        return try {
            connection = (endpoint.openConnection() as HttpURLConnection).apply {
                connectTimeout = timeout.toInt()
                readTimeout = timeout.toInt()
                instanceFollowRedirects = false
                useCaches = false
                requestMethod = "GET"
            }
            connections[requestId] = connection
            val code = connection.responseCode
            if (code !in 200..299) {
                return fail(
                    backend, requestId, started,
                    CloudPinyinFailure.HttpStatus, code.toString()
                )
            }
            val body = connection.inputStream.use {
                readBody(it, deadlineMillis = started + timeout)
            }
            val reason = when (body) {
                BodyReadResult.Empty -> CloudPinyinFailure.EmptyBody
                BodyReadResult.Oversize -> CloudPinyinFailure.Oversize
                BodyReadResult.Timeout -> CloudPinyinFailure.Timeout
                is BodyReadResult.Content -> null
            }
            if (reason != null) {
                return fail(backend, requestId, started, reason)
            }
            val text = decodeUtf8((body as BodyReadResult.Content).bytes)
                ?: return fail(backend, requestId, started, CloudPinyinFailure.InvalidUtf8)

            when (val parsed = backend.parse(text)) {
                is CloudPinyinParseResult.Candidate -> {
                    Log.d(
                        TAG,
                        "provider=${backend.id} request=$requestId status=$code candidate=ok " +
                            "elapsed=${System.currentTimeMillis() - started}"
                    )
                    CloudPinyinFetchResult.Success(parsed.value)
                }

                is CloudPinyinParseResult.Failure -> {
                    logFailure(
                        backend, requestId, started,
                        parsed.reason, parsed.detail, code
                    )
                    CloudPinyinFetchResult.Failure(parsed.reason, parsed.detail)
                }
            }
        } catch (e: SocketTimeoutException) {
            fail(backend, requestId, started, CloudPinyinFailure.Timeout, e.javaClass.simpleName)
        } catch (e: Exception) {
            fail(
                backend, requestId, started,
                CloudPinyinFailure.Network, e.javaClass.simpleName
            )
        } finally {
            connections.remove(requestId)
            runCatching { connection?.disconnect() }
        }
    }

    fun disconnect(requestId: Long) {
        val connection = connections.remove(requestId) ?: return
        runCatching { connection.disconnect() }
    }

    /**
     * Reads at most [maxBytes]. An oversized body is rejected before the excess is
     * buffered.
     */
    fun readBody(
        input: InputStream,
        deadlineMillis: Long? = null
    ): BodyReadResult {
        val buffer = ByteArray(1024)
        val bytes = ByteArrayOutputStream()
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            total += read
            if (total > maxBytes) return BodyReadResult.Oversize
            if (deadlineMillis != null && System.currentTimeMillis() >= deadlineMillis) {
                return BodyReadResult.Timeout
            }
            bytes.write(buffer, 0, read)
        }
        if (total == 0) return BodyReadResult.Empty
        return BodyReadResult.Content(bytes.toByteArray())
    }

    private fun fail(
        backend: CloudPinyinBackend,
        requestId: Long,
        started: Long,
        reason: CloudPinyinFailure,
        detail: String? = null
    ): CloudPinyinFetchResult {
        logFailure(backend, requestId, started, reason, detail, null)
        return CloudPinyinFetchResult.Failure(reason, detail)
    }

    private fun logFailure(
        backend: CloudPinyinBackend,
        requestId: Long,
        started: Long,
        reason: CloudPinyinFailure,
        detail: String?,
        httpCode: Int?
    ) {
        Log.w(
            TAG,
            "provider=${backend.id} request=$requestId " +
                "status=${httpCode ?: "-"} reason=${reason.name.lowercase()} " +
                "detail=${detail ?: "-"} elapsed=${System.currentTimeMillis() - started}"
        )
    }

    private companion object {
        const val TAG = "CloudPinyin"
    }
}
