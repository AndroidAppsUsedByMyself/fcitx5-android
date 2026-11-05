/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.cloud_pinyin

import org.junit.Assert.assertEquals
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
}
