/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.cloud_pinyin

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout

data class CloudPinyinConfig(
    val backends: List<CloudPinyinBackend>,
    val fallbackEnabled: Boolean
)

/**
 * Query orchestration: cache, offline short circuit, per-endpoint backoff, duplicate
 * merging, concurrency limit and a single overall time budget.
 *
 * When fallback is disabled only the first configured backend is ever attempted. When it
 * is enabled, backends are tried serially in list order and the first candidate wins.
 */
class CloudPinyinRepository(
    private val scope: CoroutineScope,
    private val configProvider: () -> CloudPinyinConfig,
    private val onlineCheck: () -> Boolean,
    private val fetcher: suspend (
        backend: CloudPinyinBackend,
        pinyin: String,
        requestId: Long,
        timeoutMillis: Long
    ) -> CloudPinyinFetchResult,
    private val cache: CloudPinyinCache = CloudPinyinCache(),
    private val backoff: CloudPinyinBackoff = CloudPinyinBackoff(),
    /**
     * Whole fallback chain budget: one direct attempt (1s) plus one proxied attempt
     * (3s). The host IPC timeout is 5s, so the final result is not dropped.
     */
    private val timeoutMillis: Long = 4000L,
    maxConcurrent: Int = 3,
    private val disconnect: (Long) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val semaphore = Semaphore(maxConcurrent)
    private val mutex = Mutex()
    private val inflight = mutableMapOf<String, Deferred<String?>>()

    /** Request id that owns the fetch for a key; only its cancellation disconnects. */
    private val holders = mutableMapOf<String, Long>()

    suspend fun query(pinyin: String, requestId: Long): String? {
        val config = configProvider()
        if (config.backends.isEmpty()) return null
        if (!onlineCheck()) return null

        val normalized = pinyin.trim().lowercase()
        if (normalized.isEmpty()) return null

        val attempts = if (config.fallbackEnabled) {
            config.backends
        } else {
            config.backends.take(1)
        }
        val key = cacheKey(config.fallbackEnabled, attempts, normalized)

        cache.get(key)?.let { return it }

        val deferred = mutex.withLock {
            inflight[key] ?: scope.async {
                fetchSequentially(attempts, normalized, requestId, key)
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
     * Called when the provider list changes. Proxy identities are part of backend keys,
     * so this clears results and cooldowns that belonged to the previous configuration.
     */
    fun onSettingsChanged() {
        cache.clear()
        backoff.clear()
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

    private fun cacheKey(
        fallbackEnabled: Boolean,
        attempts: List<CloudPinyinBackend>,
        normalized: String
    ): String {
        val mode = if (fallbackEnabled) "fallback" else "single"
        val backends = attempts.joinToString("\u0001") { it.key }
        return "$mode\u0000$backends\u0000$normalized"
    }

    private suspend fun fetchSequentially(
        attempts: List<CloudPinyinBackend>,
        pinyin: String,
        requestId: Long,
        key: String
    ): String? {
        val deadline = clock() + timeoutMillis
        for (backend in attempts) {
            if (backoff.isCoolingDown(backend.key)) continue
            val remaining = deadline - clock()
            if (remaining <= 0) break

            val result = try {
                semaphore.withPermit {
                    withTimeout(remaining) {
                        fetcher(backend, pinyin, requestId, remaining)
                    }
                }
            } catch (e: TimeoutCancellationException) {
                backoff.recordFailure(backend.key)
                disconnect(requestId)
                continue
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                backoff.recordFailure(backend.key)
                continue
            }

            when (result) {
                is CloudPinyinFetchResult.Success -> {
                    if (result.candidate.isNotEmpty()) {
                        cache.put(key, result.candidate)
                        backoff.recordSuccess(backend.key)
                        return result.candidate
                    }
                    backoff.recordFailure(backend.key)
                }

                is CloudPinyinFetchResult.Failure -> backoff.recordFailure(backend.key)
            }
        }
        return null
    }
}
