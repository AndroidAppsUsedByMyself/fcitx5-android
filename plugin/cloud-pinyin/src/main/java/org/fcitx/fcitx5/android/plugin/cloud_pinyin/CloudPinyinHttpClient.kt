/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.cloud_pinyin

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * HTTPS-only fetcher with strict timeouts and a hard response size limit.
 */
class CloudPinyinHttpClient(private val maxBytes: Int = 4 * 1024) {

    private val connections = ConcurrentHashMap<Long, HttpURLConnection>()

    fun fetch(backend: CloudPinyinBackend, pinyin: String, requestId: Long): String? {
        val urlString = backend.requestUrl(pinyin) ?: return null
        val url = runCatching { URL(urlString) }.getOrNull() ?: return null
        if (url.protocol != "https") return null

        val started = System.currentTimeMillis()
        var connection: HttpURLConnection? = null
        return try {
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MILLIS
                readTimeout = READ_TIMEOUT_MILLIS
                instanceFollowRedirects = false
                useCaches = false
                requestMethod = "GET"
            }
            connections[requestId] = connection
            val code = connection.responseCode
            if (code !in 200..299) {
                Log.d(TAG, "request=$requestId status=$code elapsed=${System.currentTimeMillis() - started}")
                return null
            }
            val body = connection.inputStream.use { readBounded(it) } ?: return null
            val candidate = backend.parseResponse(body)
            Log.d(
                TAG,
                "request=$requestId status=$code candidate=${if (candidate.isNullOrEmpty()) "none" else "ok"} " +
                    "elapsed=${System.currentTimeMillis() - started}"
            )
            candidate
        } catch (e: Exception) {
            Log.d(TAG, "request=$requestId failed exception=${e.javaClass.simpleName}")
            null
        } finally {
            connections.remove(requestId)
            runCatching { connection?.disconnect() }
        }
    }

    fun disconnect(requestId: Long) {
        val connection = connections.remove(requestId) ?: return
        runCatching { connection.disconnect() }
    }

    /**
     * Reads at most [maxBytes]. Returns `null` when the response exceeds the limit or is
     * empty, so oversized bodies are never buffered.
     */
    fun readBounded(input: InputStream): String? {
        val buffer = ByteArray(1024)
        var total = 0
        val bytes = ByteArrayOutputStream()
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            total += read
            if (total > maxBytes) return null
            bytes.write(buffer, 0, read)
        }
        // Decode once so multi-byte UTF-8 characters split across chunks stay intact.
        return bytes.toString(Charsets.UTF_8.name()).takeIf { it.isNotEmpty() }
    }

    private companion object {
        const val TAG = "CloudPinyin"
        const val CONNECT_TIMEOUT_MILLIS = 300
        const val READ_TIMEOUT_MILLIS = 300
    }
}
