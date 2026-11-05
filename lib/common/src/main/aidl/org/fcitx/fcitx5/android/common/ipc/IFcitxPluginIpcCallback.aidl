package org.fcitx.fcitx5.android.common.ipc;

/** Result channel for [IFcitxPluginIpc]. */
oneway interface IFcitxPluginIpcCallback {
    /**
     * @param requestId echoed request id; late replies are discarded by the host
     * @param status one of the codes in [PluginIpcResult]
     * @param data opaque payload
     */
    void onResult(long requestId, int status, in @nullable byte[] data);
}
