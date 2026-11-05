/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core.data

import android.content.pm.PackageManager

/**
 * Resolve a component declared as a fully qualified class name or as a relative/short
 * name against the component names that actually exist in an APK.
 *
 * Manifest merger expands relative names against the module namespace, while the
 * applicationId may carry a debug suffix. [availableComponents] comes from PackageManager
 * (`GET_SERVICES` / `GET_ACTIVITIES`), so the namespace-based name wins over the legacy
 * applicationId expansion.
 *
 * Returns null when the declaration does not match exactly one component.
 */
fun resolveDeclaredComponent(
    packageName: String,
    declared: String,
    availableComponents: Collection<String>
): String? {
    if (declared.isBlank()) return null
    if (!declared.startsWith(".") && '.' in declared) {
        return if (availableComponents.isEmpty() || declared in availableComponents) {
            declared
        } else {
            null
        }
    }
    val legacy = if (declared.startsWith(".")) packageName + declared else declared
    if (availableComponents.isEmpty() || legacy in availableComponents) return legacy
    val suffix = if (declared.startsWith(".")) declared else ".$declared"
    return availableComponents.filter { it.endsWith(suffix) }.singleOrNull()
}

/**
 * Resolve [declared] against the activities actually declared by [packageName].
 */
fun resolveSettingsActivityClass(
    packageManager: PackageManager,
    packageName: String,
    declared: String
): String? {
    @Suppress("DEPRECATION")
    val activities = runCatching {
        packageManager.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES)
            .activities?.map { it.name }.orEmpty()
    }.getOrElse { emptyList() }
    return resolveSettingsActivityClass(packageName, declared, activities)
}

/**
 * Testable variant of the PackageManager lookup.
 */
fun resolveSettingsActivityClass(
    packageName: String,
    declared: String,
    availableActivities: Collection<String>
): String? = resolveDeclaredComponent(packageName, declared, availableActivities)
