/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.cloud_pinyin

import android.content.Context

/**
 * Semantic text colours used by the programmatically built settings screen.
 *
 * The palette itself is defined once in res/values/colors.xml (Catppuccin Latte) and
 * res/values-night/colors.xml (Catppuccin Mocha), together with res/values/themes.xml,
 * so the activity follows the system night mode. Adding another flavour later only
 * requires another colour resource set (or a theme overlay) with the same names; no
 * view code has to change.
 */
class CatppuccinPalette(context: Context) {
    val text = context.getColor(R.color.cp_text)
    val textSecondary = context.getColor(R.color.cp_text_secondary)
}
