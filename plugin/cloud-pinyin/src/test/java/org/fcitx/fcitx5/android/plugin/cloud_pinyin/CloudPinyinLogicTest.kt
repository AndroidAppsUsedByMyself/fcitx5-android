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
    private val backend = CloudPinyinBackend.Google

    @Test
    fun testCoolDownStartsAtThreeFailures() {
        backoff.recordFailure(backend)
        backoff.recordFailure(backend)
        assertTrue(!backoff.isCoolingDown(backend))
        backoff.recordFailure(backend)
        assertTrue(backoff.isCoolingDown(backend))
        now += 60_000
        assertTrue(!backoff.isCoolingDown(backend))
    }

    @Test
    fun testEscalationAndSuccessReset() {
        repeat(4) { backoff.recordFailure(backend) }
        now += 60_000
        assertTrue(backoff.isCoolingDown(backend))
        now += 60_000
        assertTrue(!backoff.isCoolingDown(backend))
        backoff.recordSuccess(backend)
        backoff.recordFailure(backend)
        backoff.recordFailure(backend)
        assertTrue(!backoff.isCoolingDown(backend))
    }
}

class CloudPinyinHttpClientTest {

    @Test
    fun testBoundedReadRejectsOversize() {
        val client = CloudPinyinHttpClient(maxBytes = 4 * 1024)
        val body = "a".repeat(4097)
        assertNull(client.readBounded(body.byteInputStream()))
        assertEquals("a".repeat(4096), client.readBounded("a".repeat(4096).byteInputStream()))
    }
}
