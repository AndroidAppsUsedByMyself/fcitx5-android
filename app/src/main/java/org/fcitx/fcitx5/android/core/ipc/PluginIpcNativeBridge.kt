/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core.ipc

import org.fcitx.fcitx5.android.core.FcitxPluginServices

/**
 * JNI entry points for the `androidipcbridge` native addon.
 *
 * Native code calls [request] / [cancel]; the host calls [onResult] back into native,
 * which then delivers the reply on the fcitx thread.
 */
object PluginIpcNativeBridge {

    init {
        System.loadLibrary("native-lib")
        FcitxPluginServices.setResultSink { requestId, status, data ->
            nativeIpcResponse(requestId, status, data)
        }
    }

    /**
     * Called from native.
     *
     * @return `false` when the request cannot be dispatched at all.
     */
    @JvmStatic
    fun request(requestId: Long, method: String, params: ByteArray?): Boolean =
        FcitxPluginServices.request(requestId, method, params)

    /** Called from native. */
    @JvmStatic
    fun cancel(requestId: Long) = FcitxPluginServices.cancel(requestId)

    /** Called from Kotlin when a plugin reply arrives; delivers it to the native addon. */
    fun onResult(requestId: Long, status: Int, data: ByteArray?) {
        nativeIpcResponse(requestId, status, data)
    }

    @JvmStatic
    external fun nativeIpcResponse(requestId: Long, status: Int, data: ByteArray?)
}
