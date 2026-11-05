/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.cloud_pinyin

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView

/**
 * Minimal backend selection UI. Both backends are off by default.
 */
class CloudPinyinSettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val padding = (16 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        val title = TextView(this).apply { text = getString(R.string.settings_backend_title) }
        layout.addView(title)

        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val backends = listOf(
            CloudPinyinBackend.Off to R.string.settings_backend_off,
            CloudPinyinBackend.Google to R.string.settings_backend_google,
            CloudPinyinBackend.Baidu to R.string.settings_backend_baidu
        )
        val selected = CloudPinyinSettings.backend(this)
        backends.forEach { (backend, label) ->
            group.addView(RadioButton(this).apply {
                id = backend.ordinal + 1
                text = getString(label)
                isChecked = backend == selected
            })
        }
        group.setOnCheckedChangeListener { _, checkedId ->
            val backend = backends[checkedId - 1].first
            CloudPinyinSettings.setBackend(this, backend)
        }
        layout.addView(group)

        layout.addView(TextView(this).apply {
            text = getString(R.string.settings_disclaimer)
            setPadding(0, padding, 0, 0)
        })

        setContentView(layout)
    }
}
