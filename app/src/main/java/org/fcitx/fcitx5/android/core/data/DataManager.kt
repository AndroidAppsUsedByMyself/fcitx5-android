/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2024 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core.data

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.res.AssetManager
import android.os.Build
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.common.plugin.PluginManifestParser
import org.fcitx.fcitx5.android.common.plugin.PluginManifestResult
import org.fcitx.fcitx5.android.common.plugin.PluginService
import org.fcitx.fcitx5.android.common.util.PathSafety
import org.fcitx.fcitx5.android.core.data.DataManager.dataDir
import org.fcitx.fcitx5.android.utils.FileUtil
import org.fcitx.fcitx5.android.utils.appContext
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Build up a Filesystem hierarchy at [dataDir]
 *
 * Operations are synchronized
 */
object DataManager {

    data class PluginSet(
        val loaded: Set<PluginDescriptor>,
        val failed: Map<String, PluginLoadFailed>
    )

    private val lock = ReentrantLock()

    private val json by lazy { Json { prettyPrint = true } }

    var synced = false
        private set

    // should be consistent with the deserialization in DataDescriptorPlugin (:build-logic)
    private fun deserializeDataDescriptor(raw: String): DataDescriptor {
        return json.decodeFromString<DataDescriptor>(raw)
    }

    private fun serializeDataDescriptor(descriptor: DataDescriptor): String {
        return json.encodeToString(descriptor)
    }

    // If Android version supports direct boot, we put the hierarchy in device encrypted storage
    // instead of credential encrypted storage so that data can be accessed before user unlock
    val dataDir: File = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        Timber.d("Using device protected storage")
        appContext.createDeviceProtectedStorageContext().dataDir
    } else {
        File(appContext.applicationInfo.dataDir)
    }

    private fun AssetManager.getDataDescriptor(): DataDescriptor {
        return open(BuildConfig.DATA_DESCRIPTOR_NAME)
            .bufferedReader()
            .use { it.readText() }
            .let { deserializeDataDescriptor(it) }
    }

    private val loadedPlugins = mutableSetOf<PluginDescriptor>()
    private val failedPlugins = mutableMapOf<String, PluginLoadFailed>()

    fun getLoadedPlugins(): Set<PluginDescriptor> = loadedPlugins
    fun getFailedPlugins(): Map<String, PluginLoadFailed> = failedPlugins

    fun getSyncedPluginSet() = PluginSet(loadedPlugins, failedPlugins)

    /**
     * Will be cleared after each sync
     */
    private val callbacks = mutableListOf<() -> Unit>()

    fun addOnNextSyncedCallback(block: () -> Unit) =
        callbacks.add(block)

    fun detectPlugins(signaturePolicy: PluginSignaturePolicy = PluginTrust.policy()): PluginSet {
        val toLoad = mutableSetOf<PluginDescriptor>()
        val preloadFailed = mutableMapOf<String, PluginLoadFailed>()

        val pm = appContext.packageManager

        val hits = mutableMapOf<String, MutableSet<String>>()
        for (action in PluginManifestActions.all) {
            queryPluginActivities(action).forEach {
                hits.getOrPut(it.activityInfo.packageName) { mutableSetOf() }.add(action)
            }
        }
        val matches = resolvePluginMatches(hits)

        Timber.d("Detected plugin packages: ${matches.joinToString { it.packageName }}")

        // Parse and validate plugin.xml
        for (match in matches) {
            val packageName = match.packageName
            val res = pm.getResourcesForApplication(packageName)
            val result = PluginManifestParser.parse(res, packageName)
            if (result !is PluginManifestResult.Parsed) {
                when (result) {
                    PluginManifestResult.Missing -> {
                        Timber.w("Plugin descriptor is missing for $packageName")
                        preloadFailed[packageName] = PluginLoadFailed.MissingPluginDescriptor
                    }
                    is PluginManifestResult.Invalid -> {
                        Timber.w("Invalid plugin descriptor for $packageName: ${result.rejection.technicalMessage}")
                        preloadFailed[packageName] = PluginLoadFailed.PluginDescriptorParseError
                    }
                }
                continue
            }
            val manifest = result.manifest
            val apiVersion = manifest.apiVersion

            if (PluginDescriptor.pluginAPI == apiVersion) {
                val service = manifest.service
                if (service != null && !isServiceAvailable(pm, packageName, service)) {
                    Timber.w("Declared service '${service.component}' with action '${service.action}' is unavailable in $packageName")
                    preloadFailed[packageName] =
                        PluginLoadFailed.ServiceUnavailable(packageName, service.component, service.action)
                    continue
                }
                val info = getPluginPackageInfo(pm, packageName)
                val origin = signaturePolicy.originOf(signerFingerprints(info))
                toLoad.add(
                    PluginDescriptor(
                        packageName = packageName,
                        matchedAction = match.matchedAction,
                        origin = origin,
                        apiVersion = apiVersion,
                        domain = manifest.domain,
                        description = manifest.description ?: "",
                        provides = manifest.provides,
                        service = service,
                        hasService = manifest.hasService || service != null,
                        versionName = info.versionName ?: "",
                        nativeLibraryDir = info.applicationInfo?.nativeLibraryDir ?: ""
                    )
                )
            } else {
                Timber.w("$packageName's api version [$apiVersion] doesn't match with the current [${PluginDescriptor.pluginAPI}]")
                preloadFailed[packageName] = PluginLoadFailed.PluginAPIIncompatible(apiVersion)
            }
        }
        return PluginSet(toLoad, preloadFailed)
    }

    fun queryPluginActivities(action: String): List<ResolveInfo> {
        val pm = appContext.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(
                Intent(action),
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
            )
        } else {
            pm.queryIntentActivities(Intent(action), PackageManager.MATCH_ALL)
        }
    }

    private fun isServiceAvailable(
        pm: PackageManager,
        packageName: String,
        service: PluginService
    ): Boolean {
        val component = ComponentName(
            packageName,
            if (service.component.startsWith(".")) "$packageName${service.component}"
            else service.component
        )
        val componentExists = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getServiceInfo(component, PackageManager.ComponentInfoFlags.of(0L))
            } else {
                pm.getServiceInfo(component, 0)
            }
        }.isSuccess
        val services = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentServices(
                Intent(service.action).setPackage(packageName),
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
            )
        } else {
            pm.queryIntentServices(
                Intent(service.action).setPackage(packageName),
                PackageManager.MATCH_ALL
            )
        }
        val actionExists = services.any { it.serviceInfo.name == component.className }
        return verifyServiceDeclaration(componentExists, actionExists)
    }

    @Suppress("DEPRECATION")
    private fun getPluginPackageInfo(pm: PackageManager, packageName: String): PackageInfo {
        val signerFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val flags = PackageManager.GET_META_DATA or signerFlag
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            pm.getPackageInfo(packageName, flags)
        }
    }

    @Suppress("DEPRECATION")
    private fun signerFingerprints(info: PackageInfo): Set<String> {
        val signers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            info.signatures
        }
        return signers.orEmpty().map { it.toByteArray().sha256Hex() }.toSet()
    }

    private fun ByteArray.sha256Hex(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(this)
        return buildString(digest.size * 2) {
            digest.forEach { append((it.toInt() and 0xFF).toString(16).padStart(2, '0')) }
        }
    }

    fun sync() = lock.withLock {
        synced = false
        loadedPlugins.clear()
        failedPlugins.clear()

        val destDescriptorFile = File(dataDir, BuildConfig.DATA_DESCRIPTOR_NAME)

        // load last run's data descriptor
        val oldDescriptor = destDescriptorFile
            .runCatching { deserializeDataDescriptor(bufferedReader().use { it.readText() }) }
            .getOrElse { DataDescriptor("", emptyMap(), emptyMap()) }

        // load app's data descriptor
        val mainDescriptor = appContext.assets.getDataDescriptor()

        val (parsedDescriptors, failed) = detectPlugins()
        failedPlugins.putAll(failed)

        Timber.d("Plugins to load: $parsedDescriptors")

        // Create an empty hierarchy
        val newHierarchy = DataHierarchy()
        // Always add app's first
        newHierarchy.install(mainDescriptor, FileSource.Main)

        val pluginAssets = mutableMapOf<String, AssetManager>()

        // Add plugin's one by one
        for (plugin in parsedDescriptors) {
            val pluginContext = appContext.createPackageContext(plugin.packageName, 0)
            val assets = pluginContext.assets
            val descriptor = runCatching { assets.getDataDescriptor() }.onFailure {
                Timber.w("Failed to get or decode data descriptor of '${plugin.packageName}'")
                Timber.w(it)
            }.getOrNull() ?: continue
            try {
                newHierarchy.install(descriptor, FileSource.Plugin(plugin))
            } catch (e: DataHierarchy.PathConflict) {
                Timber.w("Path '${e.path}' has already been created by '${e.src}', cannot create file")
                failedPlugins[plugin.packageName] =
                    PluginLoadFailed.PathConflict(plugin, e.path, e.src)
                continue
            } catch (e: DataHierarchy.SymlinkConflict) {
                Timber.w("Path '${e.path}' has already been created by '${e.src}', cannot create symlink")
                failedPlugins[plugin.packageName] =
                    PluginLoadFailed.PathConflict(plugin, e.path, e.src)
                continue
            } catch (e: DataHierarchy.PathEscape) {
                Timber.w("Asset path '${e.path}' escapes the plugin root")
                failedPlugins[plugin.packageName] =
                    PluginLoadFailed.AssetPathViolation(plugin, e.path)
                continue
            }
            pluginAssets[plugin.packageName] = assets
            loadedPlugins.add(plugin)
            Timber.d("Merged data hierarchy of ${plugin.packageName}")
        }

        Timber.d("Hierarchy created")

        // Compute the difference of the created one and the old one
        // Run actions to migrate to the new hierarchy
        DataHierarchy.diff(oldDescriptor, newHierarchy).sortedByDescending { it.ordinal }.forEach {
            Timber.d("Action: $it")
            when (it) {
                is FileAction.CreateFile -> {
                    val assets = if (it.src is FileSource.Plugin)
                        pluginAssets.getValue(it.src.descriptor.packageName)
                    else appContext.assets
                    assets.copyFile(it.path)
                }
                is FileAction.DeleteDir -> {
                    removePath(it.path).getOrThrow()
                }
                is FileAction.DeleteFile -> {
                    removePath(it.path).getOrThrow()
                }
                is FileAction.UpdateFile -> {
                    val assets = if (it.src is FileSource.Plugin)
                        pluginAssets.getValue(it.src.descriptor.packageName)
                    else appContext.assets
                    assets.copyFile(it.path)
                }
                is FileAction.CreateSymlink -> {
                    removePath(it.path).getOrThrow()
                    symlink(it.src, it.path).getOrThrow()
                }
            }
        }
        // save the new hierarchy as the data descriptor to be used in the next run
        destDescriptorFile.bufferedWriter().use {
            it.write(serializeDataDescriptor(newHierarchy.downToDataDescriptor()))
        }
        callbacks.forEach { it() }
        callbacks.clear()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // remove old assets from credential encrypted storage
            val oldDataDir = appContext.dataDir
            val oldDataDescriptor = oldDataDir.resolve(BuildConfig.DATA_DESCRIPTOR_NAME)
            if (oldDataDescriptor.exists()) {
                oldDataDescriptor.delete()
                oldDataDir.resolve("README.md").delete()
                oldDataDir.resolve("usr").deleteRecursively()
            }
        }
        synced = true
        Timber.d("Synced")
    }

    private fun removePath(path: String) =
        FileUtil.removeFile(dataDir.resolve(path))

    private fun symlink(source: String, target: String) =
        FileUtil.symlink(dataDir.resolve(source), dataDir.resolve(target), dataDir)

    private fun AssetManager.copyFile(filename: String) {
        val path = PathSafety.normalizeRelative(filename)
            ?: throw IOException("Unsafe asset path: '$filename'")
        val target = File(dataDir, path)
        if (!PathSafety.isWithin(dataDir, target)) {
            throw IOException("Asset path escapes data directory: '$filename'")
        }
        open(path).use { i ->
            target
                .also { it.parentFile?.mkdirs() }
                .outputStream()
                .use { o -> i.copyTo(o) }
        }
    }

    fun deleteAndSync() {
        lock.withLock {
            dataDir.resolve(BuildConfig.DATA_DESCRIPTOR_NAME).delete()
            dataDir.resolve("README.md").delete()
            dataDir.resolve("usr").deleteRecursively()
        }
        sync()
    }

}

/**
 * A declared service is usable only when both its component and action resolve in the APK.
 */
internal fun verifyServiceDeclaration(componentExists: Boolean, actionExists: Boolean): Boolean =
    componentExists && actionExists
