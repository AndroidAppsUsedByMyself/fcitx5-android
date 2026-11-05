/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.cloud_pinyin

import android.app.Service
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.common.ipc.IFcitxPluginIpc
import org.fcitx.fcitx5.android.common.ipc.IFcitxPluginIpcCallback
import org.fcitx.fcitx5.android.common.ipc.PluginIpcProtocol
import org.fcitx.fcitx5.android.common.ipc.PluginIpcResult
import java.util.concurrent.ConcurrentHashMap

/**
 * Host facing IPC endpoint of the cloud pinyin plugin.
 */
class CloudPinyinService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val httpClient = CloudPinyinHttpClient()

    private val repository by lazy {
        CloudPinyinRepository(
            scope = scope,
            backendProvider = { CloudPinyinSettings.backend(this) },
            onlineCheck = ::isOnline,
            fetcher = { backend, pinyin, requestId ->
                httpClient.fetch(backend, pinyin, requestId)
            },
            disconnect = httpClient::disconnect
        )
    }

    private val jobs = ConcurrentHashMap<Long, Job>()

    private val binder = object : IFcitxPluginIpc.Stub() {

        override fun getProtocolVersion(): Int = PluginIpcProtocol.VERSION

        override fun getPluginId(): String = packageName

        override fun onIpcRequest(
            requestId: Long,
            method: String?,
            params: ByteArray?,
            callback: IFcitxPluginIpcCallback?
        ) {
            if (callback == null) return
            if (method != METHOD_QUERY || params == null) {
                reply(callback, requestId, PluginIpcResult.Rejected, null)
                return
            }
            val pinyin = runCatching { params.toString(Charsets.UTF_8) }.getOrNull()
            if (pinyin.isNullOrBlank()) {
                reply(callback, requestId, PluginIpcResult.Rejected, null)
                return
            }
            val job = scope.launch {
                val started = System.currentTimeMillis()
                val result = repository.query(pinyin, requestId)
                if (!isActive) return@launch
                val status = if (result.isNullOrEmpty()) PluginIpcResult.Unavailable
                else PluginIpcResult.Ok
                Log.d(
                    TAG,
                    "request=$requestId method=$METHOD_QUERY status=$status " +
                        "elapsed=${System.currentTimeMillis() - started}"
                )
                reply(
                    callback,
                    requestId,
                    status,
                    result?.toByteArray(Charsets.UTF_8)
                )
            }
            jobs[requestId] = job
            job.invokeOnCompletion { jobs.remove(requestId) }
        }

        override fun cancelRequest(requestId: Long) {
            jobs.remove(requestId)?.cancel()
            repository.cancel(requestId)
        }
    }

    private fun reply(
        callback: IFcitxPluginIpcCallback,
        requestId: Long,
        result: PluginIpcResult,
        data: ByteArray?
    ) {
        runCatching { callback.onResult(requestId, result.code, data) }
    }

    private fun isOnline(): Boolean {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "CloudPinyin"
        const val METHOD_QUERY = "cloudpinyin.query"
    }
}
