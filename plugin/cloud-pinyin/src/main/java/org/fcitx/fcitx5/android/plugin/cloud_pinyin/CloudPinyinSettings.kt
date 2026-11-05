/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.cloud_pinyin

import android.content.Context
import android.content.SharedPreferences

data class CloudPinyinSettingsSnapshot(
    val providers: List<ProviderEntry>,
    val fallbackEnabled: Boolean
)

/**
 * Plugin-local settings. The default list contains only the recommended Google CN
 * provider, and fallback is off until the user opts in.
 */
object CloudPinyinSettings {

    private const val PREFS = "cloud_pinyin"
    private const val KEY_PROVIDERS = "providers"
    private const val KEY_FALLBACK = "fallback"
    private const val KEY_LEGACY_BACKEND = "backend"

    val defaultProviders: List<ProviderEntry> =
        listOf(ProviderEntry(CloudPinyinProvider.GoogleCn))

    fun snapshot(context: Context): CloudPinyinSettingsSnapshot {
        val prefs = prefs(context)
        val raw = prefs.getString(KEY_PROVIDERS, null)
        val providers = if (raw != null) {
            ProviderListCodec.decode(raw)
        } else {
            legacyProviders(prefs)
        }
        return CloudPinyinSettingsSnapshot(
            providers = providers,
            fallbackEnabled = prefs.getBoolean(KEY_FALLBACK, false)
        )
    }

    fun setProviders(context: Context, providers: List<ProviderEntry>) {
        prefs(context).edit()
            .putString(KEY_PROVIDERS, ProviderListCodec.encode(providers))
            .remove(KEY_LEGACY_BACKEND)
            .apply()
    }

    fun setFallback(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_FALLBACK, enabled).apply()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Previous versions stored a single backend under `backend`. Keep the user's explicit
     * choice; an explicit "off" migrates to an empty list, while an absent key uses the
     * new default.
     */
    private fun legacyProviders(prefs: SharedPreferences): List<ProviderEntry> =
        when (prefs.getString(KEY_LEGACY_BACKEND, null)) {
            CloudPinyinProvider.Google.id -> listOf(ProviderEntry(CloudPinyinProvider.Google))
            CloudPinyinProvider.Baidu.id -> listOf(ProviderEntry(CloudPinyinProvider.Baidu))
            "off" -> emptyList()
            else -> defaultProviders
        }
}
