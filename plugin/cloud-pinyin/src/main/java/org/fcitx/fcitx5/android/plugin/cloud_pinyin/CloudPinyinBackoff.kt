/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.cloud_pinyin

/**
 * Per-endpoint consecutive failure backoff.
 *
 * 3 failures -> 60s, 4 -> 120s, 5+ -> 300s. Any success resets the counter.
 */
class CloudPinyinBackoff(
    private val clock: () -> Long = System::currentTimeMillis
) {
    private data class State(var failures: Int = 0, var cooldownUntil: Long = 0)

    private val states = mutableMapOf<CloudPinyinBackend, State>()

    @Synchronized
    fun isCoolingDown(backend: CloudPinyinBackend): Boolean =
        (states[backend]?.cooldownUntil ?: 0) > clock()

    @Synchronized
    fun recordFailure(backend: CloudPinyinBackend) {
        val state = states.getOrPut(backend) { State() }
        state.failures += 1
        if (state.failures >= 3) {
            state.cooldownUntil = clock() + delayFor(state.failures)
        }
    }

    @Synchronized
    fun recordSuccess(backend: CloudPinyinBackend) {
        states.remove(backend)
    }

    private fun delayFor(failures: Int): Long = when {
        failures >= 5 -> 300_000L
        failures == 4 -> 120_000L
        else -> 60_000L
    }
}
