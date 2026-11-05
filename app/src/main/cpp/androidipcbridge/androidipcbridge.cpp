/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

#include "androidipcbridge.h"

#include <utility>

#include <fcitx/addonfactory.h>
#include <fcitx/addonmanager.h>

namespace fcitx {

/**
 * Business-agnostic forwarding addon.
 *
 * It owns no endpoint, performs no caching or retry, and never touches the network: a
 * request is handed to whichever dispatcher the host installed at startup.
 */
class AndroidIpcBridge final : public AddonInstance {
public:
    bool dispatchable() { return static_cast<bool>(dispatch_); }

    uint64_t request(const std::string &method, const AndroidIpcPayload &params,
                     AndroidIpcCallback callback) {
        if (!dispatch_) {
            if (callback) {
                callback(AndroidIpcStatus::Unavailable, {});
            }
            return 0;
        }
        // The dispatcher guarantees the callback has already been invoked when it
        // returns 0, so it must be called exactly once in either case.
        return dispatch_(method, params, std::move(callback));
    }

    void cancel(uint64_t requestId) {
        if (cancel_) {
            cancel_(requestId);
        }
    }

    void setDispatcher(AndroidIpcDispatch dispatch, AndroidIpcCancel cancel) {
        dispatch_ = std::move(dispatch);
        cancel_ = std::move(cancel);
    }

    FCITX_ADDON_EXPORT_FUNCTION(AndroidIpcBridge, dispatchable);
    FCITX_ADDON_EXPORT_FUNCTION(AndroidIpcBridge, request);
    FCITX_ADDON_EXPORT_FUNCTION(AndroidIpcBridge, cancel);
    FCITX_ADDON_EXPORT_FUNCTION(AndroidIpcBridge, setDispatcher);

private:
    AndroidIpcDispatch dispatch_;
    AndroidIpcCancel cancel_;
};

class AndroidIpcBridgeFactory final : public AddonFactory {
public:
    AddonInstance *create(AddonManager *manager) override {
        FCITX_UNUSED(manager);
        return new AndroidIpcBridge;
    }
};

FCITX_ADDON_FACTORY_V2(androidipcbridge, AndroidIpcBridgeFactory);

} // namespace fcitx
