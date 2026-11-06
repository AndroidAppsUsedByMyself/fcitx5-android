/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.cloud_pinyin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudPinyinCacheTest {

    @Test
    fun testLruEviction() {
        val cache = CloudPinyinCache(capacity = 2)
        cache.put("a", "1")
        cache.put("b", "2")
        assertEquals("1", cache.get("a"))
        cache.put("c", "3")
        assertNull(cache.get("b"))
        assertEquals("1", cache.get("a"))
        assertEquals("3", cache.get("c"))
    }

    @Test
    fun testExpiry() {
        var now = 0L
        val cache = CloudPinyinCache(capacity = 4, ttlMillis = 100, clock = { now })
        cache.put("a", "1")
        now = 101
        assertNull(cache.get("a"))
    }
}

class CloudPinyinBackoffTest {

    private var now = 0L
    private val backoff = CloudPinyinBackoff { now }
    private val backendKey = "googlecn"

    @Test
    fun testCoolDownStartsAtThreeFailures() {
        backoff.recordFailure(backendKey)
        backoff.recordFailure(backendKey)
        assertTrue(!backoff.isCoolingDown(backendKey))
        backoff.recordFailure(backendKey)
        assertTrue(backoff.isCoolingDown(backendKey))
        now += 60_000
        assertTrue(!backoff.isCoolingDown(backendKey))
    }

    @Test
    fun testEscalationAndSuccessReset() {
        repeat(4) { backoff.recordFailure(backendKey) }
        now += 60_000
        assertTrue(backoff.isCoolingDown(backendKey))
        now += 60_000
        assertTrue(!backoff.isCoolingDown(backendKey))
        backoff.recordSuccess(backendKey)
        backoff.recordFailure(backendKey)
        backoff.recordFailure(backendKey)
        assertTrue(!backoff.isCoolingDown(backendKey))
    }

    @Test
    fun testEndpointsAreIsolated() {
        repeat(3) { backoff.recordFailure("a") }
        assertTrue(backoff.isCoolingDown("a"))
        assertTrue(!backoff.isCoolingDown("b"))
    }
}

class CloudPinyinHttpClientTest {

    private val client = CloudPinyinHttpClient(maxBytes = 4 * 1024)

    @Test
    fun testBoundedReadRejectsOversize() {
        val body = "a".repeat(4097)
        assertEquals(BodyReadResult.Oversize, client.readBody(body.byteInputStream()))
        assertEquals(BodyReadResult.Empty, client.readBody("".byteInputStream()))

        val content = client.readBody("a".repeat(4096).byteInputStream())
        assertTrue(content is BodyReadResult.Content)
        assertEquals(
            "a".repeat(4096),
            String((content as BodyReadResult.Content).bytes, Charsets.UTF_8)
        )
    }

    @Test
    fun testEndpointRequiresHttps() {
        val insecure = checkEndpoint("http://example.com/cloud")
        assertTrue(insecure is EndpointCheck.Invalid)
        assertEquals(
            CloudPinyinFailure.InsecureScheme,
            (insecure as EndpointCheck.Invalid).reason
        )
        assertTrue(checkEndpoint("not a url") is EndpointCheck.Invalid)
        assertTrue(checkEndpoint(null) is EndpointCheck.Invalid)
        assertTrue(checkEndpoint("https://example.com/cloud") is EndpointCheck.Valid)
    }

    @Test
    fun testStrictUtf8() {
        assertEquals("你好", decodeUtf8("你好".toByteArray(Charsets.UTF_8)))
        assertNull(decodeUtf8(byteArrayOf(0x61, 0xFF.toByte())))
        assertNull(decodeUtf8(byteArrayOf(0xE4.toByte(), 0xBD.toByte())))
    }
}

class ProviderListCodecTest {

    @Test
    fun testRoundTrip() {
        val entries = listOf(
            ProviderEntry(CloudPinyinProvider.GoogleCn),
            ProviderEntry(CloudPinyinProvider.Baidu)
        )
        assertEquals(entries, ProviderListCodec.decode(ProviderListCodec.encode(entries)))
    }

    @Test
    fun testEmptyAndUnknown() {
        assertEquals(emptyList<ProviderEntry>(), ProviderListCodec.decode(""))
        assertEquals(
            listOf(ProviderEntry(CloudPinyinProvider.Google)),
            ProviderListCodec.decode("google\nunknown\n")
        )
    }

    @Test
    fun testCustomUrlRoundTrip() {
        val entries = listOf(
            ProviderEntry(
                CloudPinyinProvider.Custom,
                "https://example.com/cloud?a=1&b=2"
            ),
            ProviderEntry(CloudPinyinProvider.GoogleCn)
        )
        assertEquals(entries, ProviderListCodec.decode(ProviderListCodec.encode(entries)))
    }

    @Test
    fun testCustomWithoutUrlIsDropped() {
        assertEquals(emptyList<ProviderEntry>(), ProviderListCodec.decode("custom"))
        assertEquals(
            emptyList<ProviderEntry>(),
            ProviderListCodec.decode("custom\t")
        )
    }

    @Test
    fun testProxyRoundTrip() {
        val entries = listOf(
            ProviderEntry(
                CloudPinyinProvider.GoogleCn,
                proxy = ProxyConfig(
                    ProxyKind.Http, "proxy.example.com", 8080, "user", "cred-1"
                )
            ),
            ProviderEntry(
                CloudPinyinProvider.Baidu,
                proxy = ProxyConfig(ProxyKind.Socks5, "127.0.0.1", 1080)
            ),
            ProviderEntry(
                CloudPinyinProvider.Custom,
                "https://example.com/cloud?a=1&b=2",
                ProxyConfig()
            )
        )
        assertEquals(entries, ProviderListCodec.decode(ProviderListCodec.encode(entries)))
    }

    @Test
    fun testSocksAuthFieldsSurviveRoundTrip() {
        val entry = ProviderEntry(
            CloudPinyinProvider.GoogleCn,
            proxy = ProxyConfig(
                ProxyKind.Socks5, "proxy.example.com", 1080, "user", "cred-2"
            )
        )
        assertEquals(
            listOf(entry),
            ProviderListCodec.decode(ProviderListCodec.encode(listOf(entry)))
        )
    }

    @Test
    fun testInvalidProxyPortSurvivesRoundTrip() {
        val entry = ProviderEntry(
            CloudPinyinProvider.GoogleCn,
            proxy = ProxyConfig(ProxyKind.Http, "proxy.example.com", 0)
        )
        assertEquals(
            listOf(entry),
            ProviderListCodec.decode(ProviderListCodec.encode(listOf(entry)))
        )
    }

    @Test
    fun testUnknownProxyKindDecodesDirect() {
        val decoded = ProviderListCodec.decode("google\t\tsocks6\tproxy\t1080")
        assertEquals(
            listOf(ProviderEntry(CloudPinyinProvider.Google)),
            decoded
        )
    }
}

class ProxyConfigTest {

    @Test
    fun testBackendKeyIncludesProxyIdentity() {
        val direct = GoogleCnBackend()
        val proxied = GoogleCnBackend(
            ProxyConfig(ProxyKind.Http, "proxy.example.com", 8080, "user", "cred")
        )
        assertNotEquals(direct.key, proxied.key)
    }

    @Test
    fun testIdentityDoesNotContainUsernameOrPassword() {
        val config = ProxyConfig(
            ProxyKind.Http, "proxy.example.com", 8080, "user", "cred"
        )
        assertTrue("user" !in config.identity)
        assertTrue("cred" in config.identity)
    }

    @Test
    fun testValidation() {
        assertTrue(ProxyConfig().isValid)
        assertTrue(ProxyConfig(ProxyKind.Http, "host", 1).isValid)
        assertTrue(ProxyConfig(ProxyKind.Http, "host", 65535).isValid)
        assertTrue(!ProxyConfig(ProxyKind.Http, "", 8080).isValid)
        assertTrue(!ProxyConfig(ProxyKind.Http, "host", 0).isValid)
        assertTrue(!ProxyConfig(ProxyKind.Http, "host", 65536).isValid)
    }
}
