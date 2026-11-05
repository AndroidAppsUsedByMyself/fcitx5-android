/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.cloud_pinyin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CloudPinyinRepositoryTest {

    private class TestBackend(override val id: String) : CloudPinyinBackend {
        override val key: String get() = id
        override fun requestUrl(pinyin: String) = "https://example.com/?q=$pinyin"
        override fun parse(body: String) = CloudPinyinParseResult.Candidate(body)
    }

    private fun repository(
        scope: CoroutineScope,
        backends: List<CloudPinyinBackend> = listOf(TestBackend("a")),
        fallback: Boolean = false,
        online: Boolean = true,
        timeoutMillis: Long = 900L,
        backoff: CloudPinyinBackoff = CloudPinyinBackoff(),
        fetcher: suspend (CloudPinyinBackend, String, Long, Long) -> CloudPinyinFetchResult
    ) = CloudPinyinRepository(
        scope = scope,
        configProvider = { CloudPinyinConfig(backends, fallback) },
        onlineCheck = { online },
        fetcher = fetcher,
        backoff = backoff,
        timeoutMillis = timeoutMillis
    )

    @Test
    fun testOfflineShortCircuitsWithoutFetch() = runBlocking {
        var calls = 0
        val result = repository(this, online = false) { _, _, _, _ ->
            calls += 1
            CloudPinyinFetchResult.Success("候选")
        }.query("nihao", 1)
        assertNull(result)
        assertEquals(0, calls)
    }

    @Test
    fun testEmptyProviderListSkipsFetch() = runBlocking {
        var calls = 0
        val result = repository(this, backends = emptyList()) { _, _, _, _ ->
            calls += 1
            CloudPinyinFetchResult.Success("候选")
        }.query("nihao", 1)
        assertNull(result)
        assertEquals(0, calls)
    }

    @Test
    fun testDuplicateQueriesShareOneFetch() = runBlocking {
        var calls = 0
        val repo = repository(this) { _, _, _, _ ->
            calls += 1
            delay(50)
            CloudPinyinFetchResult.Success("候选")
        }
        val a = async { repo.query("nihao", 1) }
        val b = async { repo.query("nihao", 2) }
        assertEquals(listOf("候选", "候选"), awaitAll(a, b))
        assertEquals(1, calls)
    }

    @Test
    fun testTimeoutReturnsNull() = runBlocking {
        val repo = repository(this, timeoutMillis = 50) { _, _, _, _ ->
            delay(1000)
            CloudPinyinFetchResult.Success("候选")
        }
        assertNull(repo.query("nihao", 1))
    }

    @Test
    fun testSuccessIsCached() = runBlocking {
        var calls = 0
        val repo = repository(this) { _, _, _, _ ->
            calls += 1
            CloudPinyinFetchResult.Success("候选")
        }
        assertEquals("候选", repo.query("nihao", 1))
        assertEquals("候选", repo.query("nihao", 2))
        assertEquals(1, calls)
    }

    @Test
    fun testProviderChangeInvalidatesCache() = runBlocking {
        var backends = listOf<CloudPinyinBackend>(TestBackend("a"))
        var calls = 0
        val repo = CloudPinyinRepository(
            scope = this,
            configProvider = { CloudPinyinConfig(backends, false) },
            onlineCheck = { true },
            fetcher = { backend, _, _, _ ->
                calls += 1
                CloudPinyinFetchResult.Success(backend.id)
            }
        )
        assertEquals("a", repo.query("nihao", 1))
        backends = listOf(TestBackend("b"))
        assertEquals("b", repo.query("nihao", 2))
        assertEquals(2, calls)
    }

    @Test
    fun testCancellingWaiterKeepsHolderResult() = runBlocking {
        var disconnects = 0
        val repo = CloudPinyinRepository(
            scope = this,
            configProvider = { CloudPinyinConfig(listOf(TestBackend("a")), false) },
            onlineCheck = { true },
            fetcher = { _, _, _, _ ->
                delay(100)
                CloudPinyinFetchResult.Success("候选")
            },
            disconnect = { disconnects += 1 }
        )
        val holder = async { repo.query("nihao", 1) }
        delay(20)
        val waiter = async { repo.query("nihao", 2) }
        delay(20)
        repo.cancel(2)
        delay(20)
        assertEquals("候选", holder.await())
        assertEquals("候选", waiter.await())
        assertEquals(0, disconnects)
    }

    @Test
    fun testCancellingHolderDisconnects() = runBlocking {
        var disconnects = 0
        val repo = CloudPinyinRepository(
            scope = this,
            configProvider = { CloudPinyinConfig(listOf(TestBackend("a")), false) },
            onlineCheck = { true },
            fetcher = { _, _, _, _ ->
                delay(200)
                CloudPinyinFetchResult.Success("候选")
            },
            disconnect = { disconnects += 1 }
        )
        val holder = async { repo.query("nihao", 1) }
        delay(30)
        repo.cancel(1)
        delay(30)
        runCatching { holder.await() }
        assertEquals(1, disconnects)
    }

    @Test
    fun testFailureBacksOff() = runBlocking {
        var calls = 0
        val repo = repository(this) { _, _, _, _ ->
            calls += 1
            CloudPinyinFetchResult.Failure(CloudPinyinFailure.Network)
        }
        repeat(3) { repo.query("pinyin$it", it.toLong()) }
        assertNull(repo.query("another", 9))
        assertEquals(3, calls)
    }

    @Test
    fun testFallbackOffOnlyTriesFirstProvider() = runBlocking {
        val calls = mutableListOf<String>()
        val repo = repository(
            this,
            backends = listOf(TestBackend("a"), TestBackend("b"))
        ) { backend, _, _, _ ->
            calls += backend.id
            if (backend.id == "a") {
                CloudPinyinFetchResult.Failure(CloudPinyinFailure.Network)
            } else {
                CloudPinyinFetchResult.Success("候选")
            }
        }
        assertNull(repo.query("nihao", 1))
        assertEquals(listOf("a"), calls)
    }

    @Test
    fun testFallbackOnTriesNextProvider() = runBlocking {
        val calls = mutableListOf<String>()
        val repo = repository(
            this,
            backends = listOf(TestBackend("a"), TestBackend("b")),
            fallback = true
        ) { backend, _, _, _ ->
            calls += backend.id
            if (backend.id == "a") {
                CloudPinyinFetchResult.Failure(CloudPinyinFailure.Network)
            } else {
                CloudPinyinFetchResult.Success("候选")
            }
        }
        assertEquals("候选", repo.query("nihao", 1))
        assertEquals(listOf("a", "b"), calls)
    }

    @Test
    fun testFallbackStopsAtFirstSuccess() = runBlocking {
        val calls = mutableListOf<String>()
        val repo = repository(
            this,
            backends = listOf(TestBackend("a"), TestBackend("b")),
            fallback = true
        ) { backend, _, _, _ ->
            calls += backend.id
            CloudPinyinFetchResult.Success("候选-${backend.id}")
        }
        assertEquals("候选-a", repo.query("nihao", 1))
        assertEquals(listOf("a"), calls)
    }

    @Test
    fun testFallbackSkipsCoolingProvider() = runBlocking {
        val backoff = CloudPinyinBackoff()
        repeat(3) { backoff.recordFailure("a") }
        val calls = mutableListOf<String>()
        val repo = repository(
            this,
            backends = listOf(TestBackend("a"), TestBackend("b")),
            fallback = true,
            backoff = backoff
        ) { backend, _, _, _ ->
            calls += backend.id
            CloudPinyinFetchResult.Success("候选-${backend.id}")
        }
        assertEquals("候选-b", repo.query("nihao", 1))
        assertEquals(listOf("b"), calls)
    }
}
