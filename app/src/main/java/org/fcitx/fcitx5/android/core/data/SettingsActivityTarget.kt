/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core.data

/**
 * Resolve a settings activity class declared as either a relative name (`.Foo`) or a
 * fully qualified class name.
 */
fun resolveSettingsActivityClass(packageName: String, declared: String): String =
    if (declared.startsWith(".")) packageName + declared else declared
