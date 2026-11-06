/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.cloud_pinyin

import android.util.Log
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.InterruptedIOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

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

/** Outcome of a settings-dialog connectivity test. */
data class ConnectivityProbeResult(
    val ok: Boolean,
    val reason: CloudPinyinFailure? = null,
    val detail: String? = null,
    val elapsedMillis: Long
)

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
 *
 * Each request gets its own OkHttp client (and an empty connection pool) so credentials
 * for one provider's proxy can never be reused for another provider that happens to
 * share the same proxy address. [OkHttpClient.callTimeout] bounds the whole attempt:
 * DNS, TCP CONNECT, proxy 407 negotiation, TLS and the response body read.
 */
class CloudPinyinHttpClient(
    private val maxBytes: Int = 4 * 1024,
    /** Direct requests measured at 108-377ms; 1s leaves room for cold TLS. */
    private val maxAttemptMillis: Long = 1000L,
    /**
     * Proxied requests add a proxy TCP handshake, CONNECT/407 negotiation and TLS.
     * Measured: SOCKS5 cold start 1085ms; HTTP CONNECT on the same LAN 0.9-1.4s with
     * occasional 2.05s spikes that the previous 2s cap cut off. 3s covers the worst
     * observed attempt with margin while still failing fast on a dead proxy.
     */
    private val proxiedMaxAttemptMillis: Long = 3000L,
    private val credentialStore: ProxyCredentialStore? = null
) {

    private val calls = ConcurrentHashMap<Long, Call>()

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
        val plan = ProxyPlanner.resolve(backend.proxy, credentialStore)
        if (plan is ProxyPlan.Failure) {
            logFailure(backend, requestId, started, plan.reason, plan.detail, null)
            return CloudPinyinFetchResult.Failure(plan.reason, plan.detail)
        }
        val attemptCap = if (backend.proxy.enabled) {
            proxiedMaxAttemptMillis
        } else {
            maxAttemptMillis
        }
        val timeout = timeoutMillis.coerceIn(1L, attemptCap)
        val client = buildClient(backend.proxy, plan, timeout)
        val call = client.newCall(
            Request.Builder().url(endpoint).get().build()
        )
        calls[requestId] = call
        return try {
            call.execute().use { response ->
                val code = response.code
                if (code == 407 && backend.proxy.enabled) {
                    return fail(
                        backend, requestId, started,
                        CloudPinyinFailure.ProxyAuth, code.toString()
                    )
                }
                if (code !in 200..299) {
                    return fail(
                        backend, requestId, started,
                        CloudPinyinFailure.HttpStatus, code.toString()
                    )
                }
                val responseBody = response.body
                    ?: return fail(
                        backend, requestId, started,
                        CloudPinyinFailure.EmptyBody
                    )
                val body = responseBody.byteStream().use {
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
                    ?: return fail(
                        backend, requestId, started,
                        CloudPinyinFailure.InvalidUtf8
                    )

                when (val parsed = backend.parse(text)) {
                    is CloudPinyinParseResult.Candidate -> {
                        Log.d(
                            TAG,
                            "provider=${backend.id} proxy=${backend.proxy.logLabel} " +
                                "request=$requestId status=$code candidate=ok " +
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
            }
        } catch (e: SocketTimeoutException) {
            val reason = if (backend.proxy.enabled) {
                CloudPinyinFailure.ProxyTimeout
            } else {
                CloudPinyinFailure.Timeout
            }
            fail(backend, requestId, started, reason, e.javaClass.simpleName)
        } catch (e: InterruptedIOException) {
            val reason = if (backend.proxy.enabled) {
                CloudPinyinFailure.ProxyTimeout
            } else {
                CloudPinyinFailure.Timeout
            }
            fail(backend, requestId, started, reason, e.javaClass.simpleName)
        } catch (e: IOException) {
            val message = e.message.orEmpty()
            val reason = when {
                !backend.proxy.enabled -> CloudPinyinFailure.Network
                "407" in message -> CloudPinyinFailure.ProxyAuth
                "authenticate" in message.lowercase() -> CloudPinyinFailure.ProxyAuth
                else -> CloudPinyinFailure.ProxyNetwork
            }
            fail(backend, requestId, started, reason, e.javaClass.simpleName)
        } catch (e: Exception) {
            val reason = if (backend.proxy.enabled) {
                CloudPinyinFailure.ProxyNetwork
            } else {
                CloudPinyinFailure.Network
            }
            fail(backend, requestId, started, reason, e.javaClass.simpleName)
        } finally {
            calls.remove(requestId)
            call.cancel()
        }
    }

    fun disconnect(requestId: Long) {
        calls.remove(requestId)?.cancel()
    }

    /**
     * One-off connectivity check for the settings dialog.
     *
     * It performs the same request as a real lookup (proxy CONNECT, authentication, TLS
     * and the provider response) with the values currently typed into the dialog. It
     * stores nothing and its outcome never blocks saving the configuration.
     */
    fun probe(
        backend: CloudPinyinBackend,
        proxy: ProxyConfig,
        password: String?,
        timeoutMillis: Long = proxiedMaxAttemptMillis
    ): ConnectivityProbeResult {
        val started = System.currentTimeMillis()
        fun result(
            ok: Boolean,
            reason: CloudPinyinFailure? = null,
            detail: String? = null
        ) = ConnectivityProbeResult(ok, reason, detail, System.currentTimeMillis() - started)

        val endpoint = when (val check = checkEndpoint(backend.requestUrl(PROBE_PINYIN))) {
            is EndpointCheck.Invalid -> return result(false, check.reason, check.detail)
            is EndpointCheck.Valid -> check.url
        }
        val plan = ProxyPlanner.resolve(proxy, credentialStore, password)
        if (plan is ProxyPlan.Failure) {
            return result(false, plan.reason, plan.detail)
        }
        val attemptCap = if (proxy.enabled) proxiedMaxAttemptMillis else maxAttemptMillis
        val timeout = timeoutMillis.coerceIn(1L, attemptCap)
        val client = buildClient(proxy, plan, timeout)
        val call = client.newCall(Request.Builder().url(endpoint).get().build())
        val failure = try {
            call.execute().use { response ->
                val code = response.code
                return when {
                    code == 407 && proxy.enabled ->
                        result(false, CloudPinyinFailure.ProxyAuth, code.toString())

                    code !in 200..299 ->
                        result(false, CloudPinyinFailure.HttpStatus, code.toString())

                    else -> result(true, detail = code.toString())
                }
            }
        } catch (e: SocketTimeoutException) {
            CloudPinyinFailure.ProxyTimeout to e.message
        } catch (e: InterruptedIOException) {
            CloudPinyinFailure.ProxyTimeout to e.message
        } catch (e: IOException) {
            val message = e.message.orEmpty()
            val reason = when {
                !proxy.enabled -> CloudPinyinFailure.Network
                // SOCKS5 authentication is not implemented, so a proxy that demands it
                // is reported as unsupported rather than as a generic network failure.
                proxy.kind == ProxyKind.Socks5 &&
                    "authentication" in message.lowercase() ->
                    CloudPinyinFailure.ProxyAuthUnsupported

                "407" in message -> CloudPinyinFailure.ProxyAuth
                "authenticate" in message.lowercase() -> CloudPinyinFailure.ProxyAuth
                else -> CloudPinyinFailure.ProxyNetwork
            }
            reason to e.message
        } catch (e: Exception) {
            val reason = if (proxy.enabled) {
                CloudPinyinFailure.ProxyNetwork
            } else {
                CloudPinyinFailure.Network
            }
            reason to e.message
        } finally {
            call.cancel()
        }

        // OkHttp reports a rejected CONNECT as a truncated tunnel instead of a 407, so
        // ask the proxy directly to distinguish bad credentials from a broken network.
        if (proxy.enabled && proxy.kind == ProxyKind.Http &&
            failure.first != CloudPinyinFailure.ProxyAuth
        ) {
            val credentials = (plan as? ProxyPlan.Proxied)?.credentials
            val status = credentials?.let {
                runCatching {
                    rawTunnelStatus(
                        proxy,
                        it,
                        endpoint.host,
                        endpointPort(endpoint),
                        timeout
                    )
                }.getOrNull()
            }
            if (status == 407) {
                return result(false, CloudPinyinFailure.ProxyAuth, status.toString())
            }
        }
        return result(false, failure.first, failure.second)
    }

    /**
     * Minimal CONNECT handshake used only to learn the proxy's status code. The tunnel is
     * closed immediately; the real probe request is done by OkHttp.
     */
    private fun rawTunnelStatus(
        proxy: ProxyConfig,
        credentials: ProxyCredentials,
        host: String,
        port: Int,
        timeoutMillis: Long
    ): Int {
        Socket().use { socket ->
            socket.connect(
                InetSocketAddress(proxy.host.trim(), proxy.port),
                timeoutMillis.toInt()
            )
            socket.soTimeout = timeoutMillis.toInt()
            val authorization = Credentials.basic(credentials.username, credentials.password)
            val request = buildString {
                append("CONNECT $host:$port HTTP/1.1\r\n")
                append("Host: $host:$port\r\n")
                append("Proxy-Authorization: $authorization\r\n")
                append("\r\n")
            }
            socket.getOutputStream().apply {
                write(request.toByteArray(Charsets.ISO_8859_1))
                flush()
            }
            val reader = BufferedReader(
                InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1)
            )
            val statusLine = reader.readLine().orEmpty()
            return statusLine.split(' ').getOrNull(1)?.toIntOrNull()
                ?: throw IOException("unexpected status line: $statusLine")
        }
    }

    private fun endpointPort(url: URL): Int = if (url.port > 0) url.port else 443

    private fun buildClient(
        proxyConfig: ProxyConfig,
        plan: ProxyPlan,
        timeoutMillis: Long
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            // Never reuse a tunnel across requests: the same proxy address may be
            // configured with different credentials for different providers.
            .connectionPool(ConnectionPool(0, 1, TimeUnit.MILLISECONDS))
            .connectTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
        when (plan) {
            ProxyPlan.Direct -> builder.proxy(Proxy.NO_PROXY)
            is ProxyPlan.Proxied -> {
                builder.proxy(plan.proxy)
                plan.credentials?.let { credentials ->
                    builder.proxyAuthenticator { _, response ->
                        // Only answer the first challenge; otherwise OkHttp would
                        // retry endlessly when the credentials are wrong.
                        if (response.code == 407 && response.priorResponse == null) {
                            response.request.newBuilder()
                                .header(
                                    "Proxy-Authorization",
                                    Credentials.basic(
                                        credentials.username,
                                        credentials.password
                                    )
                                )
                                .build()
                        } else {
                            null
                        }
                    }
                }
            }

            is ProxyPlan.Failure -> error("unreachable proxy plan")
        }
        return builder.build()
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
            "provider=${backend.id} proxy=${backend.proxy.logLabel} " +
                "request=$requestId status=${httpCode ?: "-"} " +
                "reason=${reason.name.lowercase()} detail=${detail ?: "-"} " +
                "elapsed=${System.currentTimeMillis() - started}"
        )
    }

    private companion object {
        const val TAG = "CloudPinyin"

        /** Input used by the connectivity test; long enough to pass the provider minimum. */
        const val PROBE_PINYIN = "nihao"
    }
}
