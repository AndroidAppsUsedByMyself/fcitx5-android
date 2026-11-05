/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

#include <string>

#include <fcitx-utils/key.h>
#include <fcitx/addonfactory.h>
#include <fcitx/addoninstance.h>
#include <fcitx/addonmanager.h>

// Vendored-in-tree header of the pinned fcitx5-chinese-addons submodule.
#include "cloudpinyin_public.h"

namespace fcitx {

/**
 * Cloud pinyin addon skeleton.
 *
 * It satisfies the interface the pinyin engine expects, but does not talk to any backend
 * yet: every request fails immediately with an empty result, which makes the pinyin
 * engine drop the placeholder candidate right away.
 */
class CloudPinyin final : public AddonInstance {
public:
    void request(const std::string &pinyin, CloudPinyinCallback callback) {
        callback(pinyin, "");
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
};

class CloudPinyinFactory final : public AddonFactory {
public:
    AddonInstance *create(AddonManager *manager) override {
        FCITX_UNUSED(manager);
        return new CloudPinyin;
    }
};

FCITX_ADDON_FACTORY_V2(cloudpinyin, CloudPinyinFactory);

} // namespace fcitx
