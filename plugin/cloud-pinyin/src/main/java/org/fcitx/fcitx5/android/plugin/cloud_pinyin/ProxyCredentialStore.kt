/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.cloud_pinyin

/**
 * Password storage for proxy authentication.
 *
 * [load] must return null both when no credential exists and when it cannot be
 * decrypted; callers must treat that as "request refused", never as "no authentication".
 */
interface ProxyCredentialStore {
    fun store(id: String, password: String): Boolean
    fun load(id: String): String?
    fun remove(id: String)
}
