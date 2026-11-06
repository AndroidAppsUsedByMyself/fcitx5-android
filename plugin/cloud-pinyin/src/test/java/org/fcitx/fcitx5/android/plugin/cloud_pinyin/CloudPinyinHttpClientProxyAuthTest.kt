/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.cloud_pinyin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Proxy
import kotlin.concurrent.thread

class CloudPinyinHttpClientProxyAuthTest {

    private class Store(private val password: String) : ProxyCredentialStore {
        override fun store(id: String, password: String) = true
        override fun load(id: String): String? = password
        override fun remove(id: String) = Unit
    }

    private class Backend(
        override val proxy: ProxyConfig
    ) : CloudPinyinBackend {
        override val id: String get() = "test"
        override val key: String get() = "test|${proxy.identity}"
        override fun requestUrl(pinyin: String) = "https://example.com/"
        override fun parse(body: String) = CloudPinyinParseResult.Candidate(body)
    }

    private fun readConnectHeaders(socket: java.net.Socket): Pair<String, String?> {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
        val requestLine = reader.readLine().orEmpty()
        var authorization: String? = null
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Proxy-Authorization:", ignoreCase = true)) {
                authorization = line.substringAfter(':').trim()
            }
        }
        return requestLine to authorization
    }

    @Test
    fun testChallengeSendsProxyAuthorization() {
        val server = ServerSocket(0)
        val observed = mutableListOf<String?>()
        val worker = thread {
            repeat(2) { _ ->
                server.accept().use { socket ->
                    val (requestLine, authorization) = readConnectHeaders(socket)
                    assertTrue(requestLine.startsWith("CONNECT "))
                    observed += authorization
                    val out = OutputStreamWriter(socket.getOutputStream(), Charsets.ISO_8859_1)
                    if (authorization == null) {
                        out.write(
                            "HTTP/1.1 407 Proxy Authentication Required\r\n" +
                                "Proxy-Authenticate: Basic realm=\"test\"\r\n\r\n"
                        )
                    } else {
                        out.write("HTTP/1.1 200 Connection Established\r\n\r\n")
                    }
                    out.flush()
                    // Closing the tunnel makes the subsequent TLS handshake fail; the
                    // test only cares about the proxy handshake.
                }
            }
        }

        try {
            val proxy = ProxyConfig(
                ProxyKind.Http, "127.0.0.1", server.localPort, "user", "id-1"
            )
            val client = CloudPinyinHttpClient(
                maxAttemptMillis = 2000,
                proxiedMaxAttemptMillis = 2000,
                credentialStore = Store("pass")
            )
            val result = client.fetch(Backend(proxy), "test", 1)
            assertTrue(result is CloudPinyinFetchResult.Failure)
        } finally {
            server.close()
            worker.join(2000)
        }
        assertEquals(1, observed.size)
        println("observed proxy headers: $observed")
        assertTrue(observed.isNotEmpty())
        assertEquals("Basic dXNlcjpwYXNz", observed.first())
    }

    @Test
    fun probeReportsRejectedProxyAuthentication() {
        val server = ServerSocket(0)
        val observed = mutableListOf<String?>()
        val worker = thread {
            // The failed OkHttp attempt plus the raw status check used for classification.
            repeat(2) {
                server.accept().use { socket ->
                    val (requestLine, authorization) = readConnectHeaders(socket)
                    assertTrue(requestLine.startsWith("CONNECT "))
                    observed += authorization
                    val out = OutputStreamWriter(socket.getOutputStream(), Charsets.ISO_8859_1)
                    out.write(
                        "HTTP/1.1 407 Proxy Authentication Required\r\n" +
                            "Proxy-Authenticate: Basic realm=\"test\"\r\n\r\n"
                    )
                    out.flush()
                }
            }
        }
        try {
            val proxy = ProxyConfig(
                ProxyKind.Http, "127.0.0.1", server.localPort, "user", "id-1"
            )
            val client = CloudPinyinHttpClient(maxAttemptMillis = 1000, proxiedMaxAttemptMillis = 1000)
            // The typed password is used directly, as in the settings dialog.
            val result = client.probe(Backend(proxy), proxy, "pass")
            assertTrue(result.ok.not())
            assertEquals(CloudPinyinFailure.ProxyAuth, result.reason)
            assertTrue(observed.isNotEmpty())
            assertTrue(observed.all { it == "Basic dXNlcjpwYXNz" })
            assertTrue(result.elapsedMillis >= 0)
        } finally {
            server.close()
            worker.join(2000)
        }
    }
}
