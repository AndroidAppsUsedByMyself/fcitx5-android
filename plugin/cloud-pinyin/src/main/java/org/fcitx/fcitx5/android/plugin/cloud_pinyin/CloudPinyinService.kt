/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.cloud_pinyin

import android.app.Service
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.common.ipc.IFcitxPluginIpc
import org.fcitx.fcitx5.android.common.ipc.IFcitxPluginIpcCallback
import org.fcitx.fcitx5.android.common.ipc.PluginIpcProtocol
import org.fcitx.fcitx5.android.common.ipc.PluginIpcResult
import java.util.concurrent.ConcurrentHashMap

/**
 * Host facing IPC endpoint of the cloud pinyin plugin.
 *
 * This is the skeleton only: the query result is always [PluginIpcResult.Unavailable] and
 * no network access happens here.
 */
class CloudPinyinService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
            val query = runCatching { params.toString(Charsets.UTF_8) }.getOrNull()
            if (query.isNullOrBlank()) {
                reply(callback, requestId, PluginIpcResult.Rejected, null)
                return
            }
            jobs[requestId] = scope.launch {
                try {
                    // TODO(3c): query the cloud backend and encode real candidates.
                    reply(callback, requestId, PluginIpcResult.Unavailable, null)
                } finally {
                    jobs.remove(requestId)
                }
            }
        }

        override fun cancelRequest(requestId: Long) {
            jobs.remove(requestId)?.cancel()
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

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val METHOD_QUERY = "cloudpinyin.query"
    }
}
