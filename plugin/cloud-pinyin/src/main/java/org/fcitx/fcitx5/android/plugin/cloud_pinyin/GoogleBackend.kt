/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.cloud_pinyin

/**
 * Google inputtools via the global google.com host.
 */
class GoogleBackend : GoogleInputToolsBackend(
    id = CloudPinyinProvider.Google.id,
    baseUrl = "https://www.google.com/inputtools/request?ime=pinyin&text="
)
