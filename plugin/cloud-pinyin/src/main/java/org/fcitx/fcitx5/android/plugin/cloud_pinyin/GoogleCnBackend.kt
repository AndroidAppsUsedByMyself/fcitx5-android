/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.cloud_pinyin

/**
 * Google inputtools via the mainland-facing google.cn host. Recommended default.
 */
class GoogleCnBackend(
    proxy: ProxyConfig = ProxyConfig()
) : GoogleInputToolsBackend(
    id = CloudPinyinProvider.GoogleCn.id,
    baseUrl = "https://www.google.cn/inputtools/request?ime=pinyin&text=",
    proxy = proxy
)
