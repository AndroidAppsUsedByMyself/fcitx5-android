package org.fcitx.fcitx5.android.common.ipc;

import org.fcitx.fcitx5.android.common.ipc.IFcitxPluginIpcCallback;

/**
 * Host to plugin request channel.
 *
 * Implemented by a plugin service, bound by the host with
 * action `${applicationId}.plugin.SERVICE`.
 */
interface IFcitxPluginIpc {
    /** Protocol version, currently 1. */
    int getProtocolVersion();

    /** Plugin identity; must equal the package name of the plugin APK. */
    String getPluginId();

    /**
     * Forward a request to the plugin. Implementations must reply through [callback]
     * exactly once per accepted request, or not at all after [cancelRequest].
     */
    void onIpcRequest(long requestId, String method, in @nullable byte[] params,
                      IFcitxPluginIpcCallback callback);

    /** Cancel a pending request; a later reply for [requestId] must be ignored. */
    void cancelRequest(long requestId);
}
