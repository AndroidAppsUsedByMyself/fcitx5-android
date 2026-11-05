/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.cloud_pinyin

import android.content.Context

/**
 * Plugin-local settings. Backend selection is off until the user opts in.
 */
object CloudPinyinSettings {

    private const val PREFS = "cloud_pinyin"
    private const val KEY_BACKEND = "backend"

    fun backend(context: Context): CloudPinyinBackend {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return CloudPinyinBackend.fromKey(prefs.getString(KEY_BACKEND, null))
    }

    fun setBackend(context: Context, backend: CloudPinyinBackend) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BACKEND, backend.key)
            .apply()
    }
}
