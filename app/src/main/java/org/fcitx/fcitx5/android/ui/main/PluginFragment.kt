/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.data.DataManager
import org.fcitx.fcitx5.android.core.data.FileSource
import org.fcitx.fcitx5.android.core.data.PluginLoadFailed
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.utils.addCategory
import org.fcitx.fcitx5.android.utils.addPreference
import org.fcitx.fcitx5.android.utils.addSwitch

class PluginFragment : PaddingPreferenceFragment() {

    private var firstRun = true

    private lateinit var synced: DataManager.PluginSet
    private lateinit var detected: DataManager.PluginSet
    private lateinit var enabledPlugins: Set<String>

    private val packageChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refreshPreferencesWhenNeeded()
        }
    }

    private fun DataManager.whenSynced(block: () -> Unit) {
        lifecycleScope.launch {
            if (!synced) {
                suspendCancellableCoroutine {
                    if (synced) {
                        it.resumeWith(Result.success(Unit))
                    } else {
                        addOnNextSyncedCallback {
                            it.resumeWith(Result.success(Unit))
                        }
                    }
                }
            }
            block.invoke()
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        DataManager.whenSynced {
            synced = DataManager.getSyncedPluginSet()
            detected = DataManager.detectPlugins()
            enabledPlugins = AppPrefs.getInstance().internal.enabledPlugins.getValue()
            preferenceScreen = createPreferenceScreen()
        }
    }

    private fun refreshPreferencesWhenNeeded() {
        DataManager.whenSynced {
            val newDetected = DataManager.detectPlugins()
            if (detected != newDetected) {
                detected = newDetected
                enabledPlugins = AppPrefs.getInstance().internal.enabledPlugins.getValue()
                preferenceScreen = createPreferenceScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        requireContext().registerReceiver(packageChangeReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        })
        /**
         * [onResume] got called after [onCreatePreferences] when the fragment is created and
         * shown for the first time
         */
        if (firstRun) {
            firstRun = false
            return
        }
        // try refresh plugin list when the user navigate back from other apps
        refreshPreferencesWhenNeeded()
    }

    override fun onPause() {
        super.onPause()
        requireContext().unregisterReceiver(packageChangeReceiver)
    }

    private fun updateEnabledPlugins(packageName: String, enabled: Boolean) {
        val current = AppPrefs.getInstance().internal.enabledPlugins.getValue().toMutableSet()
        if (enabled) {
            current.add(packageName)
        } else {
            current.remove(packageName)
        }
        AppPrefs.getInstance().internal.enabledPlugins.setValue(current)
        enabledPlugins = current
        FcitxDaemon.restartFcitx()
    }

    private fun createPreferenceScreen(): PreferenceScreen =
        preferenceManager.createPreferenceScreen(requireContext()).apply {
            val enabledLoaded = detected.loaded.filter { it.packageName in enabledPlugins }
            val disabledLoaded = detected.loaded.filter { it.packageName !in enabledPlugins }
            val allFailed = detected.failed.keys + synced.failed.keys

            if (enabledLoaded.isNotEmpty()) {
                addCategory(R.string.plugins_enabled) {
                    isIconSpaceReserved = false
                    enabledLoaded.forEach { plugin ->
                        addSwitch(
                            title = plugin.name,
                            summary = "${plugin.versionName}\n${plugin.description}",
                            checked = true
                        ) { enabled: Boolean ->
                            updateEnabledPlugins(plugin.packageName, enabled)
                        }
                    }
                }
            }

            if (disabledLoaded.isNotEmpty()) {
                addCategory(R.string.plugins_disabled) {
                    isIconSpaceReserved = false
                    disabledLoaded.forEach { plugin ->
                        addSwitch(
                            title = plugin.name,
                            summary = "${plugin.versionName}\n${plugin.description}",
                            checked = false
                        ) { enabled: Boolean ->
                            updateEnabledPlugins(plugin.packageName, enabled)
                        }
                    }
                }
            }

            if (allFailed.isNotEmpty()) {
                addCategory(R.string.plugins_failed) {
                    isIconSpaceReserved = false
                    allFailed.toSet().forEach { packageName ->
                        val reason = synced.failed[packageName] ?: detected.failed[packageName]
                        val summary = when (reason) {
                            is PluginLoadFailed.DataDescriptorParseError -> {
                                getString(R.string.invalid_data_descriptor)
                            }
                            is PluginLoadFailed.MissingDataDescriptor -> {
                                getString(R.string.missing_data_descriptor)
                            }
                            PluginLoadFailed.MissingPluginDescriptor -> {
                                getString(R.string.missing_plugin_descriptor)
                            }
                            is PluginLoadFailed.PathConflict -> {
                                val owner = when (reason.existingSrc) {
                                    FileSource.Main -> getString(R.string.main_program)
                                    is FileSource.Plugin -> reason.existingSrc.descriptor.name
                                }
                                getString(R.string.path_conflict, reason.path, owner)
                            }
                            is PluginLoadFailed.PluginAPIIncompatible -> {
                                getString(R.string.incompatible_api, reason.api)
                            }
                            PluginLoadFailed.PluginDescriptorParseError -> {
                                getString(R.string.invalid_plugin_descriptor)
                            }
                            null -> getString(R.string.plugin_load_failed_unknown)
                        }
                        addPreference(packageName, summary) {
                            startPluginAboutActivity(packageName)
                        }
                    }
                }
            }
        }

    private fun startPluginAboutActivity(pkg: String): Boolean {
        val ctx = requireContext()
        val pm = ctx.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(
                Intent(DataManager.PLUGIN_INTENT),
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
            )
        } else {
            pm.queryIntentActivities(Intent(DataManager.PLUGIN_INTENT), PackageManager.MATCH_ALL)
        }.firstOrNull {
            it.activityInfo.packageName == pkg
        }?.also {
            ctx.startActivity(Intent().apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                component = ComponentName(it.activityInfo.packageName, it.activityInfo.name)
            })
        } ?: run {
            // fallback to settings app info page if activity not found
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                data = Uri.fromParts("package", pkg, null)
            })
        }
        return true
    }

}
