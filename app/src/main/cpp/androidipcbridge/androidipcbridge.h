/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

#ifndef FCITX5_ANDROID_ANDROIDIPCB_BRIDGE_H
#define FCITX5_ANDROID_ANDROIDIPCB_BRIDGE_H

#include <cstdint>
#include <functional>
#include <string>
#include <vector>

#include <fcitx/addoninstance.h>

namespace fcitx {

using AndroidIpcPayload = std::vector<uint8_t>;

/**
 * Result codes; must stay in sync with `PluginIpcResult` on the Kotlin side.
 */
enum class AndroidIpcStatus : int {
    Ok = 0,
    Unavailable = 1,
    Timeout = 2,
    Cancelled = 3,
    ProtocolIncompatible = 4,
    Rejected = 5,
};

using AndroidIpcCallback =
        std::function<void(AndroidIpcStatus, const AndroidIpcPayload &)>;

/**
 * Dispatches one request to the host. Returns the request id, or 0 when the request
 * cannot be dispatched; in the latter case the callback has already been invoked with
 * AndroidIpcStatus::Unavailable.
 */
using AndroidIpcDispatch = std::function<uint64_t(
        const std::string &method, const AndroidIpcPayload &params,
        AndroidIpcCallback callback)>;

using AndroidIpcCancel = std::function<void(uint64_t requestId)>;

} // namespace fcitx

// FCITX_ADDON_DECLARE_FUNCTION opens namespace fcitx itself, so it must stay at global
// scope.
FCITX_ADDON_DECLARE_FUNCTION(AndroidIpcBridge, dispatchable, bool());
FCITX_ADDON_DECLARE_FUNCTION(AndroidIpcBridge, request,
                             uint64_t(const std::string &,
                                      const ::fcitx::AndroidIpcPayload &,
                                      ::fcitx::AndroidIpcCallback));
FCITX_ADDON_DECLARE_FUNCTION(AndroidIpcBridge, cancel, void(uint64_t));
FCITX_ADDON_DECLARE_FUNCTION(AndroidIpcBridge, setDispatcher,
                             void(::fcitx::AndroidIpcDispatch,
                                  ::fcitx::AndroidIpcCancel));

#endif // FCITX5_ANDROID_ANDROIDIPCB_BRIDGE_H
