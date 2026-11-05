/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.cloud_pinyin

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope

/**
 * Query orchestration: cache, offline short circuit, per-endpoint backoff, duplicate
 * merging, concurrency limit and the overall timeout.
 */
class CloudPinyinRepository(
    private val scope: CoroutineScope,
    private val backendProvider: () -> CloudPinyinBackend,
    private val onlineCheck: () -> Boolean,
    private val fetcher: suspend (CloudPinyinBackend, String, Long) -> String?,
    private val cache: CloudPinyinCache = CloudPinyinCache(),
    private val backoff: CloudPinyinBackoff = CloudPinyinBackoff(),
    private val timeoutMillis: Long = 900L,
    maxConcurrent: Int = 3,
    private val disconnect: (Long) -> Unit = {}
) {
    private val semaphore = Semaphore(maxConcurrent)
    private val mutex = Mutex()
    private val inflight = mutableMapOf<String, Deferred<String?>>()

    /** Request id that owns the fetch for a key; only its cancellation disconnects. */
    private val holders = mutableMapOf<String, Long>()

    suspend fun query(pinyin: String, requestId: Long): String? {
        val backend = backendProvider()
        if (backend == CloudPinyinBackend.Off) return null
        if (!onlineCheck()) return null

        val normalized = pinyin.trim().lowercase()
        if (normalized.isEmpty()) return null
        val key = backend.key + "\u0000" + normalized

        cache.get(key)?.let { return it }
        if (backoff.isCoolingDown(backend)) return null

        val deferred = mutex.withLock {
            inflight[key] ?: scope.async {
                fetchAndStore(backend, normalized, requestId, key)
            }.also {
                inflight[key] = it
                holders[key] = requestId
            }
        }
        return try {
            deferred.await()
        } finally {
            mutex.withLock {
                if (deferred.isCompleted && inflight[key] === deferred) {
                    inflight.remove(key)
                    holders.remove(key)
                }
            }
        }
    }

    /**
     * Cancel one waiter. Only the holder of a shared fetch may tear it down; cancelling a
     * mere waiter leaves the holder (and every other waiter) untouched.
     */
    fun cancel(requestId: Long) {
        scope.launch {
            val ownedKey = mutex.withLock {
                val key = holders.entries.firstOrNull { it.value == requestId }?.key
                    ?: return@withLock null
                holders.remove(key)
                inflight.remove(key)?.cancel()
                key
            }
            if (ownedKey != null) {
                disconnect(requestId)
            }
        }
    }

    private suspend fun fetchAndStore(
        backend: CloudPinyinBackend,
        pinyin: String,
        requestId: Long,
        key: String
    ): String? {
        return try {
            val candidate = semaphore.withPermit {
                withTimeout(timeoutMillis) {
                    fetcher(backend, pinyin, requestId)
                }
            }
            if (candidate.isNullOrEmpty()) {
                backoff.recordFailure(backend)
                null
            } else {
                cache.put(key, candidate)
                backoff.recordSuccess(backend)
                candidate
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            // overall request timeout is a normal failure, not a caller cancellation
            backoff.recordFailure(backend)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            backoff.recordFailure(backend)
            null
        }
    }
}
