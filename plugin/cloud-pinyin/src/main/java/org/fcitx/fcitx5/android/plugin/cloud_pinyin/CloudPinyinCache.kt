/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.cloud_pinyin

/**
 * In-process LRU cache with a fixed TTL. Never persisted.
 */
class CloudPinyinCache(
    private val capacity: Int = 384,
    private val ttlMillis: Long = 10 * 60 * 1000L,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private data class Entry(val value: String, val storedAt: Long)

    private val entries = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean =
            size > capacity
    }

    fun get(key: String): String? {
        val entry = entries[key] ?: return null
        if (clock() - entry.storedAt > ttlMillis) {
            entries.remove(key)
            return null
        }
        return entry.value
    }

    fun put(key: String, value: String) {
        entries[key] = Entry(value, clock())
    }

    fun size(): Int = entries.size
}
