/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_set>

#include <fcitx-utils/eventdispatcher.h>
#include <fcitx-utils/key.h>
#include <fcitx/addonfactory.h>
#include <fcitx/addoninstance.h>
#include <fcitx/addonmanager.h>
#include <fcitx/instance.h>

#include "androidipcbridge.h"
#include "cloudpinyin_public.h"

namespace fcitx {

/**
 * Cloud pinyin addon skeleton that forwards queries to the host over the generic IPC
 * bridge.
 *
 * The plugin service is the only component allowed to talk to the network; this addon
 * never contains an endpoint.
 */
class CloudPinyin final : public AddonInstance {
public:
    explicit CloudPinyin(AddonManager *manager)
        : manager_(manager),
          dispatcher_(&manager->instance()->eventDispatcher()),
          alive_(std::make_shared<std::atomic<bool>>(true)) {}

    ~CloudPinyin() override {
        alive_->store(false);
        auto *bridge = manager_->lookupAddon("androidipcbridge");
        if (bridge) {
            std::lock_guard<std::mutex> lock(inflightMutex_);
            for (const auto id: inflight_) {
                bridge->call<IAndroidIpcBridge::cancel>(id);
            }
            inflight_.clear();
        }
    }

    void request(const std::string &pinyin, CloudPinyinCallback callback) {
        // Same default as the upstream MinimumPinyinLength option.
        if (pinyin.size() < 4) {
            callback(pinyin, "");
            return;
        }
        auto *bridge = manager_->lookupAddon("androidipcbridge");
        if (!bridge) {
            callback(pinyin, "");
            return;
        }

        AndroidIpcPayload payload(pinyin.begin(), pinyin.end());
        auto alive = alive_;
        auto dispatcher = dispatcher_;
        const auto requestId = bridge->call<IAndroidIpcBridge::request>(
                METHOD_QUERY, payload,
                [alive, dispatcher, pinyin,
                 callback = std::move(callback)](AndroidIpcStatus status,
                                                 const AndroidIpcPayload &data) {
                    std::string hanzi;
                    if (status == AndroidIpcStatus::Ok && !data.empty()) {
                        hanzi.assign(reinterpret_cast<const char *>(data.data()),
                                     data.size());
                    }
                    // The bridge reports on the fcitx thread, but dispatch again so the
                    // callback is guaranteed to run there even if that changes.
                    dispatcher->schedule([alive, pinyin, hanzi,
                                          callback = std::move(callback)]() {
                        if (alive->load()) {
                            callback(pinyin, hanzi);
                        }
                    });
                });
        if (requestId != 0) {
            std::lock_guard<std::mutex> lock(inflightMutex_);
            inflight_.insert(requestId);
        }
    }

    const KeyList &toggleKey() const {
        // Toggling is handled by the plugin settings UI, not by a key combination.
        static const KeyList empty;
        return empty;
    }

    void resetError() {}

    FCITX_ADDON_EXPORT_FUNCTION(CloudPinyin, request);
    FCITX_ADDON_EXPORT_FUNCTION(CloudPinyin, toggleKey);
    FCITX_ADDON_EXPORT_FUNCTION(CloudPinyin, resetError);

private:
    static constexpr const char *METHOD_QUERY = "cloudpinyin.query";

    AddonManager *manager_;
    EventDispatcher *dispatcher_;
    std::shared_ptr<std::atomic<bool>> alive_;
    std::mutex inflightMutex_;
    std::unordered_set<uint64_t> inflight_;
};

class CloudPinyinFactory final : public AddonFactory {
public:
    AddonInstance *create(AddonManager *manager) override {
        return new CloudPinyin(manager);
    }
};

FCITX_ADDON_FACTORY_V2(cloudpinyin, CloudPinyinFactory);

} // namespace fcitx
