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
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FcitxPluginServices
import org.fcitx.fcitx5.android.core.data.AddonRuntimeSnapshot
import org.fcitx.fcitx5.android.core.data.AddonStatus
import org.fcitx.fcitx5.android.core.data.DataManager
import org.fcitx.fcitx5.android.core.data.FileSource
import org.fcitx.fcitx5.android.core.data.PluginConflictKind
import org.fcitx.fcitx5.android.core.data.PluginDescriptor
import org.fcitx.fcitx5.android.core.data.PluginLoadFailed
import org.fcitx.fcitx5.android.core.data.PluginManifestActions
import org.fcitx.fcitx5.android.core.data.PluginOrigin
import org.fcitx.fcitx5.android.core.data.PluginServiceState
import org.fcitx.fcitx5.android.core.data.PluginState
import org.fcitx.fcitx5.android.core.data.PluginStateResolver
import org.fcitx.fcitx5.android.core.data.resolveSettingsActivityClass
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.utils.addCategory
import org.fcitx.fcitx5.android.utils.addPreference

class PluginFragment : PaddingPreferenceFragment() {

    private var firstRun = true

    private val viewModel: MainViewModel by activityViewModels()

    private lateinit var synced: DataManager.PluginSet
    private lateinit var detected: DataManager.PluginSet
    private var addonRuntime = AddonRuntimeSnapshot()

    private val packageChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refreshPreferencesWhenNeeded()
        }
    }

    private suspend fun DataManager.whenSynced() {
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
    }

    private suspend fun queryAddonRuntime(): AddonRuntimeSnapshot =
        viewModel.fcitx.runOnReady {
            AddonRuntimeSnapshot(
                loaded = loadedAddonNames().toSet(),
                onDemand = onDemandAddonNames().toSet()
            )
        }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        lifecycleScope.launch {
            DataManager.whenSynced()
            synced = DataManager.getSyncedPluginSet()
            detected = DataManager.detectPlugins()
            addonRuntime = queryAddonRuntime()
            preferenceScreen = createPreferenceScreen()
        }
    }

    private fun refreshPreferencesWhenNeeded() {
        lifecycleScope.launch {
            DataManager.whenSynced()
            val newDetected = DataManager.detectPlugins()
            val newRuntime = queryAddonRuntime()
            if (detected != newDetected || addonRuntime != newRuntime) {
                detected = newDetected
                addonRuntime = newRuntime
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

    private fun createPreferenceScreen(): PreferenceScreen =
        preferenceManager.createPreferenceScreen(requireContext()).apply {
            val enabledPlugins = AppPrefs.getInstance().internal.enabledPlugins.getValue()
            val needsReload = detected.merged.any {
                it.packageName in enabledPlugins && it !in synced.merged
            } || synced.merged.any {
                it.packageName !in enabledPlugins || it !in detected.merged
            }
            if (needsReload) {
                addPreference(R.string.plugin_needs_reload, icon = R.drawable.ic_baseline_info_24) {
                    DataManager.addOnNextSyncedCallback {
                        lifecycleScope.launch {
                            synced = DataManager.getSyncedPluginSet()
                            detected = DataManager.detectPlugins()
                            addonRuntime = queryAddonRuntime()
                            preferenceScreen = createPreferenceScreen()
                        }
                    }
                    // DataManager.sync and and restart fcitx
                    FcitxDaemon.restartFcitx()
                }
            }
            val failed = synced.failed
            if (detected.merged.isEmpty() && failed.isEmpty()) {
                // use PreferenceCategory to show a divider below the "reload" preference
                addCategory(R.string.no_plugins) {
                    isIconSpaceReserved = false
                    @SuppressLint("PrivateResource")
                    // we can't hide PreferenceCategory's title,
                    // but we can make it looks like a normal preference
                    layoutResource = androidx.preference.R.layout.preference_material
                }
                return@apply
            }
            listOf(
                PluginOrigin.Official to R.string.plugins_official,
                PluginOrigin.Downstream to R.string.plugins_downstream,
                PluginOrigin.ThirdParty to R.string.plugins_third_party
            ).forEach { (origin, title) ->
                val plugins = detected.merged
                    .filter { it.origin == origin }
                    .sortedBy { it.packageName }
                if (plugins.isEmpty()) return@forEach
                addCategory(title) {
                    isIconSpaceReserved = false
                    plugins.forEach { descriptor ->
                        val state = PluginStateResolver.resolve(
                            descriptor,
                            addonRuntime,
                            PluginStateResolver.serviceStateOf(
                                descriptor,
                                FcitxPluginServices.connectionState(descriptor.packageName)
                            )
                        )
                        val enabled = descriptor.packageName in enabledPlugins
                        addPreference(SwitchPreferenceCompat(context).apply {
                            this.title = descriptor.packageName
                            key = descriptor.packageName
                            isPersistent = false
                            isChecked = enabled
                            summary = pluginSummary(descriptor, state, enabled)
                            setOnPreferenceChangeListener { _, newValue ->
                                val enable = newValue as Boolean
                                if (enable && descriptor.origin == PluginOrigin.ThirdParty) {
                                    confirmThirdPartyPlugin(descriptor)
                                } else {
                                    setPluginEnabled(descriptor, enable)
                                }
                                false
                            }
                        })
                        if (enabled && descriptor.settingsActivity != null) {
                            addPreference(R.string.plugin_open_settings) {
                                startPluginSettingsActivity(descriptor)
                            }
                        }
                    }
                }
            }
            if (failed.isNotEmpty()) {
                addCategory(R.string.plugins_failed) {
                    isIconSpaceReserved = false
                    failed.forEach { (packageName, reason) ->
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
                                getString(
                                    R.string.path_conflict,
                                    reason.path,
                                    describeSource(reason.existingSrc)
                                )
                            }
                            is PluginLoadFailed.ResourceConflict -> {
                                val text = when (reason.kind) {
                                    PluginConflictKind.Addon -> R.string.plugin_conflict_addon
                                    PluginConflictKind.Library -> R.string.plugin_conflict_library
                                    PluginConflictKind.Domain -> R.string.plugin_conflict_domain
                                }
                                getString(
                                    text,
                                    reason.resource,
                                    describeSource(reason.existingSrc)
                                )
                            }
                            is PluginLoadFailed.PluginAPIIncompatible -> {
                                getString(R.string.incompatible_api, reason.api)
                            }
                            PluginLoadFailed.PluginDescriptorParseError -> {
                                getString(R.string.invalid_plugin_descriptor)
                            }
                            is PluginLoadFailed.ServiceUnavailable -> {
                                getString(R.string.plugin_service_unavailable, reason.component)
                            }
                            is PluginLoadFailed.AssetPathViolation -> {
                                getString(R.string.plugin_asset_path_violation, reason.path)
                            }
                        }
                        addPreference(packageName, summary) {
                            startPluginAboutActivity(packageName)
                        }
                    }
                }
            }
        }

    private fun pluginSummary(
        descriptor: PluginDescriptor,
        state: PluginState.Active,
        enabled: Boolean
    ): String {
        val lines = mutableListOf("${descriptor.versionName}\n${descriptor.description}")
        lines += descriptor.signerFingerprint?.let {
            getString(R.string.plugin_signature, it.take(16))
        } ?: getString(R.string.plugin_signature_unknown)
        if (descriptor.requestsInternet) {
            lines += getString(R.string.plugin_capability_internet)
        }
        if (descriptor.declaresIpc) {
            lines += getString(R.string.plugin_capability_ipc)
        }
        if (!enabled) {
            lines += getString(R.string.plugin_disabled)
        }
        if (state.addons.isEmpty()) {
            lines += getString(R.string.plugin_addon_none)
        } else {
            val loaded = state.addons.filterValues { it == AddonStatus.Loaded }.keys
            val available = state.addons.filterValues { it == AddonStatus.AvailableNotLoaded }.keys
            val notLoaded = state.addons.filterValues { it == AddonStatus.NotLoaded }.keys
            if (loaded.isNotEmpty()) {
                lines += getString(R.string.plugin_addons_loaded, loaded.joinToString())
            }
            if (available.isNotEmpty()) {
                lines += getString(R.string.plugin_addons_available, available.joinToString())
            }
            if (notLoaded.isNotEmpty()) {
                lines += getString(R.string.plugin_addons_not_loaded, notLoaded.joinToString())
            }
        }
        when (val service = state.service) {
            PluginServiceState.NotDeclared -> {}
            is PluginServiceState.Bound -> {
                lines += service.protocolVersion?.let {
                    getString(R.string.plugin_service_bound, it)
                } ?: getString(R.string.plugin_service_bound_unknown)
            }
            is PluginServiceState.NotBound -> {
                lines += service.protocolVersion?.let {
                    getString(R.string.plugin_service_not_bound, it)
                } ?: getString(R.string.plugin_service_not_bound_unknown)
            }

            is PluginServiceState.Incompatible -> {
                lines += service.protocolVersion?.let {
                    getString(R.string.plugin_service_incompatible, it)
                } ?: getString(R.string.plugin_service_incompatible_unknown)
            }

            is PluginServiceState.Rejected -> {
                lines += getString(R.string.plugin_service_rejected, service.pluginId)
            }
        }
        return lines.joinToString("\n")
    }

    private fun setPluginEnabled(descriptor: PluginDescriptor, enabled: Boolean) {
        val prefs = AppPrefs.getInstance().internal
        val updated = prefs.enabledPlugins.getValue().toMutableSet()
        if (enabled) {
            updated.add(descriptor.packageName)
        } else {
            updated.remove(descriptor.packageName)
        }
        prefs.enabledPlugins.setValue(updated)
        Toast.makeText(
            requireContext(),
            getString(
                if (enabled) R.string.plugin_enabled_restart
                else R.string.plugin_disabled_restart
            ),
            Toast.LENGTH_SHORT
        ).show()
        DataManager.addOnNextSyncedCallback {
            lifecycleScope.launch {
                synced = DataManager.getSyncedPluginSet()
                detected = DataManager.detectPlugins()
                addonRuntime = queryAddonRuntime()
                preferenceScreen = createPreferenceScreen()
            }
        }
        FcitxDaemon.restartFcitx()
    }

    private fun confirmThirdPartyPlugin(descriptor: PluginDescriptor) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.plugin_third_party_trust_title)
            .setMessage(
                getString(R.string.plugin_third_party_trust_message, descriptor.packageName)
            )
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                setPluginEnabled(descriptor, true)
            }
            .show()
    }

    private fun startPluginSettingsActivity(descriptor: PluginDescriptor) {
        val declared = descriptor.settingsActivity ?: return
        val className = resolveSettingsActivityClass(
            requireContext().packageManager,
            descriptor.packageName,
            declared
        ) ?: run {
            showSettingsUnavailable()
            return
        }
        val component = ComponentName(descriptor.packageName, className)
        runCatching {
            startActivity(Intent().apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                this.component = component
            })
        }.onFailure {
            showSettingsUnavailable()
        }
    }

    private fun showSettingsUnavailable() {
        Toast.makeText(
            requireContext(),
            R.string.plugin_settings_unavailable,
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun startPluginAboutActivity(pkg: String): Boolean {
        val ctx = requireContext()
        val activity = PluginManifestActions.all.asSequence()
            .flatMap { DataManager.queryPluginActivities(it).asSequence() }
            .firstOrNull {
                it.activityInfo.packageName == pkg
            }
        activity?.also {
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

    private fun describeSource(src: FileSource): String = when (src) {
        FileSource.Main -> getString(R.string.main_program)
        is FileSource.Plugin -> src.descriptor.packageName
    }

}
