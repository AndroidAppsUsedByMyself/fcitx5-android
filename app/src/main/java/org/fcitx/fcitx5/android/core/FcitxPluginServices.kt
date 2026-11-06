/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.common.ipc.IFcitxPluginIpc
import org.fcitx.fcitx5.android.common.ipc.IFcitxPluginIpcCallback
import org.fcitx.fcitx5.android.common.ipc.PluginIpcHandshake
import org.fcitx.fcitx5.android.common.ipc.PluginIpcProtocol
import org.fcitx.fcitx5.android.common.ipc.PluginIpcResult
import org.fcitx.fcitx5.android.core.data.DataManager
import org.fcitx.fcitx5.android.core.ipc.PluginIpcConnectionState
import org.fcitx.fcitx5.android.core.ipc.PluginIpcSession
import org.fcitx.fcitx5.android.utils.appContext
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Host side of the plugin IPC channel.
 *
 * Connections are lazy: a plugin service is bound on the first request that needs it and
 * rebound after it dies. Every request has a fixed timeout, and a reply that arrives
 * after timeout, cancellation, or disconnect is dropped.
 */
object FcitxPluginServices {

    const val PLUGIN_SERVICE_ACTION = "${BuildConfig.APPLICATION_ID}.plugin.SERVICE"

    private class Connection(
        val packageName: String,
        val service: IFcitxPluginIpc
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val session = PluginIpcSession()

    private val connections = ConcurrentHashMap<String, Connection>()

    private val bindingGuards = ConcurrentHashMap<String, AtomicBoolean>()

    private val serviceConnections = ConcurrentHashMap<String, ServiceConnection>()

    private val timeouts = ConcurrentHashMap<Long, Job>()

    init {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(
            appContext,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val packageName = intent.data?.schemeSpecificPart ?: return
                    if (connections.containsKey(packageName) ||
                        bindingGuards.containsKey(packageName)
                    ) {
                        Timber.d("Plugin package changed, resetting IPC: $packageName")
                        resetPluginConnection(packageName)
                    }
                }
            },
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    @Volatile
    private var resultSink: ((Long, Int, ByteArray?) -> Unit)? = null

    /**
     * Install the consumer of request results. The native bridge sets it at startup; with
     * no sink installed, results are simply dropped.
     */
    fun setResultSink(sink: ((Long, Int, ByteArray?) -> Unit)?) {
        resultSink = sink
    }

    private fun emitResult(requestId: Long, result: PluginIpcResult, data: ByteArray?) {
        resultSink?.invoke(requestId, result.code, data)
    }

    fun connectionState(packageName: String): PluginIpcConnectionState =
        session.stateOf(packageName)

    fun isBound(packageName: String): Boolean =
        session.stateOf(packageName) is PluginIpcConnectionState.Connected

    /**
     * Route to the single enabled plugin that declares an explicit IPC service.
     *
     * The legacy `hasService` hint is not enough: it predates this AIDL contract.
     * Returns `null` when no plugin declares one, or when several do, in which case the
     * host refuses to guess.
     */
    private fun routeTarget(): String? {
        val candidates = DataManager.getMergedPlugins().filter { it.service != null }
        if (candidates.size != 1) {
            Timber.w("IPC routing failed: ${candidates.size} plugins declare an IPC service")
            return null
        }
        return candidates.single().packageName
    }

    /**
     * Submit a request coming from native code.
     *
     * @return `false` when the request cannot be accepted at all, so the native caller can
     * fail immediately instead of waiting for the timeout.
     */
    fun request(requestId: Long, method: String, params: ByteArray?): Boolean {
        val packageName = routeTarget() ?: return false
        when (val state = session.stateOf(packageName)) {
            is PluginIpcConnectionState.Incompatible,
            is PluginIpcConnectionState.Rejected -> {
                Timber.w("IPC request rejected for $packageName: $state")
                return false
            }

            else -> Unit
        }
        val request = PluginIpcSession.Pending(
            requestId = requestId,
            packageName = packageName,
            method = method,
            params = params
        ) { result, data -> emitResult(requestId, result, data) }
        session.enqueue(request)
        timeouts[requestId] = scope.launch {
            delay(PluginIpcProtocol.TIMEOUT_MILLIS)
            session.takeOnTimeout(requestId)?.let { pending ->
                timeouts.remove(requestId)
                pending.deliver(PluginIpcResult.Timeout, null)
            }
        }
        if (connections.containsKey(packageName)) {
            deliver(request)
        } else {
            bind(packageName)
        }
        return true
    }

    fun cancel(requestId: Long) {
        timeouts.remove(requestId)?.cancel()
        val pending = session.takeOnCancel(requestId) ?: return
        connections[pending.packageName]?.let {
            runCatching { it.service.cancelRequest(requestId) }
                .onFailure { e -> Timber.w(e, "cancelRequest failed") }
        }
        pending.deliver(PluginIpcResult.Cancelled, null)
    }

    private fun bind(packageName: String) {
        val guard = bindingGuards.computeIfAbsent(packageName) { AtomicBoolean(false) }
        if (!guard.compareAndSet(false, true)) {
            Timber.d("IPC bind already in progress for $packageName")
            return
        }
        session.markHandshaking(packageName)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                val service = IFcitxPluginIpc.Stub.asInterface(binder)
                if (service == null) {
                    Timber.w("IPC service of $packageName returned an unexpected binder")
                    onBindingUnusable(packageName)
                    return
                }
                handshake(packageName, service)
            }

            override fun onServiceDisconnected(name: ComponentName) {
                Timber.d("IPC service disconnected: $packageName")
                onConnectionLost(packageName)
            }

            override fun onBindingDied(name: ComponentName) {
                Timber.d("IPC binding died: $packageName")
                onConnectionLost(packageName)
            }

            override fun onNullBinding(name: ComponentName) {
                Timber.w("IPC service of $packageName returned a null binding")
                onBindingUnusable(packageName)
            }
        }
        val intent = Intent(PLUGIN_SERVICE_ACTION).setPackage(packageName)
        serviceConnections[packageName] = connection
        val started = runCatching {
            appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.getOrElse { e ->
            Timber.w(e, "Cannot bind IPC service of $packageName")
            false
        }
        if (!started) {
            onBindingUnusable(packageName)
        }
    }

    private fun handshake(packageName: String, service: IFcitxPluginIpc) {
        scope.launch {
            val result = runCatching {
                session.handshake(packageName, service.pluginId ?: "", service.protocolVersion)
            }.getOrElse { e ->
                Timber.w(e, "IPC handshake failed for $packageName")
                session.markUnbound(packageName)
                null
            }
            when (result) {
                PluginIpcHandshake.Accepted -> {
                    connections[packageName] = Connection(packageName, service)
                    releaseBindGuard(packageName)
                    Timber.d("IPC connected: $packageName")
                    // Deliver the requests queued while binding, but keep them tracked:
                    // their replies must still find a pending entry, otherwise they are
                    // dropped as late and the caller waits for the placeholder forever.
                    session.pendingOf(packageName).forEach { deliver(it) }
                }

                PluginIpcHandshake.Incompatible -> {
                    failAll(packageName, PluginIpcResult.ProtocolIncompatible)
                    unbind(packageName)
                    releaseBindGuard(packageName)
                }

                PluginIpcHandshake.Rejected -> {
                    failAll(packageName, PluginIpcResult.Rejected)
                    unbind(packageName)
                    releaseBindGuard(packageName)
                }

                null -> {
                    failAll(packageName, PluginIpcResult.Unavailable)
                    unbind(packageName)
                    releaseBindGuard(packageName)
                }
            }
        }
    }

    private fun deliver(pending: PluginIpcSession.Pending) {
        val connection = connections[pending.packageName] ?: run {
            pending.deliver(PluginIpcResult.Unavailable, null)
            return
        }
        val callback = object : IFcitxPluginIpcCallback.Stub() {
            override fun onResult(requestId: Long, status: Int, data: ByteArray?) {
                // replies after timeout, cancellation, or disconnect are dropped here
                val removed = session.takeOnResponse(requestId) ?: run {
                    Timber.d("Dropping late IPC reply $requestId")
                    return
                }
                timeouts.remove(requestId)?.cancel()
                removed.deliver(
                    PluginIpcResult.fromCode(status) ?: PluginIpcResult.Unavailable,
                    data
                )
            }
        }
        runCatching {
            connection.service.onIpcRequest(pending.requestId, pending.method, pending.params, callback)
        }.onFailure { e ->
            Timber.w(e, "IPC request ${pending.requestId} failed")
            onConnectionLost(connection.packageName)
        }
    }

    private fun onBindingUnusable(packageName: String) {
        failAll(packageName, PluginIpcResult.Unavailable)
        unbind(packageName)
        session.markUnbound(packageName)
        releaseBindGuard(packageName)
    }

    private fun onConnectionLost(packageName: String) {
        connections.remove(packageName)
        // Finish every pending request instead of leaving callers (and their UI) waiting
        // for a reply that can never arrive.
        failAll(packageName, PluginIpcResult.Unavailable)
        unbind(packageName)
        session.markUnbound(packageName)
        releaseBindGuard(packageName)
    }

    /**
     * A plugin package was installed, replaced or removed while the host was running.
     * Drop the old binding so the next request binds the new APK instead of reusing a
     * binder from the previous installation.
     */
    private fun resetPluginConnection(packageName: String) {
        connections.remove(packageName)
        failAll(packageName, PluginIpcResult.Unavailable)
        unbind(packageName)
        session.markUnbound(packageName)
        releaseBindGuard(packageName)
    }

    private fun unbind(packageName: String) {
        serviceConnections.remove(packageName)?.let { connection ->
            runCatching { appContext.unbindService(connection) }
                .onFailure { e -> Timber.w(e, "unbind IPC service of $packageName failed") }
        }
    }

    private fun releaseBindGuard(packageName: String) {
        bindingGuards.remove(packageName)?.set(false)
    }

    private fun failAll(packageName: String, result: PluginIpcResult) {
        session.takeAllOf(packageName).forEach { pending ->
            timeouts.remove(pending.requestId)?.cancel()
            pending.deliver(result, null)
        }
    }

    fun disconnectAll() {
        connections.keys.toList().forEach { resetPluginConnection(it) }
        connections.clear()
    }
}
