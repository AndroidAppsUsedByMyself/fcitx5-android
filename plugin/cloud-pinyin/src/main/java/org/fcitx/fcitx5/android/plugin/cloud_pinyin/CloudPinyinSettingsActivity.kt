/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.cloud_pinyin

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * Ordered provider list, fallback switch and provider management.
 */
class CloudPinyinSettingsActivity : Activity() {

    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        scroll.addView(content)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        content.removeAllViews()
        val snapshot = CloudPinyinSettings.snapshot(this)

        content.addView(heading(getString(R.string.settings_providers_title)))
        if (snapshot.providers.isEmpty()) {
            content.addView(body(getString(R.string.settings_providers_empty)))
        }
        snapshot.providers.forEachIndexed { index, entry ->
            content.addView(providerRow(entry, index, snapshot.providers))
        }
        content.addView(action(getString(R.string.settings_add_provider)) {
            showAddProviderDialog(snapshot.providers)
        })

        content.addView(spacer())
        content.addView(Switch(this).apply {
            text = getString(R.string.settings_fallback_title)
            isChecked = snapshot.fallbackEnabled
            setPadding(0, dp(8), 0, dp(4))
            setOnCheckedChangeListener { _, checked ->
                CloudPinyinSettings.setFallback(this@CloudPinyinSettingsActivity, checked)
            }
        })
        content.addView(body(getString(R.string.settings_fallback_warning)))
        content.addView(body(getString(R.string.settings_disclaimer)))
    }

    private fun providerRow(
        entry: ProviderEntry,
        index: Int,
        all: List<ProviderEntry>
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@CloudPinyinSettingsActivity).apply {
                text = getString(entry.provider.labelRes)
                setTypeface(Typeface.DEFAULT_BOLD)
            })
            entry.provider.endpointLabel?.let { endpoint ->
                addView(TextView(this@CloudPinyinSettingsActivity).apply {
                    text = endpoint
                    textSize = 12f
                    alpha = 0.7f
                })
            }
        }
        row.addView(labels)
        row.addView(smallButton(getString(R.string.settings_provider_up), index > 0) {
            move(index, -1, all)
        })
        row.addView(smallButton(getString(R.string.settings_provider_down), index < all.lastIndex) {
            move(index, +1, all)
        })
        row.addView(smallButton(getString(R.string.settings_provider_remove), true) {
            val updated = all.filterIndexed { i, _ -> i != index }
            CloudPinyinSettings.setProviders(this, updated)
            render()
        })
        return row
    }

    private fun move(index: Int, delta: Int, all: List<ProviderEntry>) {
        val target = index + delta
        if (target !in all.indices) return
        val updated = all.toMutableList().apply { add(target, removeAt(index)) }
        CloudPinyinSettings.setProviders(this, updated)
        render()
    }

    private fun showAddProviderDialog(existing: List<ProviderEntry>) {
        val providers = CloudPinyinProvider.entries
        val labels = providers.map { getString(it.labelRes) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_add_provider)
            .setItems(labels) { _, which ->
                val provider = providers[which]
                if (existing.any { it.provider == provider }) {
                    Toast.makeText(
                        this,
                        R.string.settings_provider_duplicate,
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setItems
                }
                CloudPinyinSettings.setProviders(this, existing + ProviderEntry(provider))
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun heading(text: String) = TextView(this).apply {
        this.text = text
        textSize = 18f
        setTypeface(Typeface.DEFAULT_BOLD)
        setPadding(0, dp(12), 0, dp(4))
    }

    private fun body(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun action(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        setOnClickListener { onClick() }
    }

    private fun smallButton(text: String, enabled: Boolean, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isEnabled = enabled
        minWidth = 0
        minimumWidth = 0
        textSize = 14f
        setPadding(dp(8), 0, dp(8), 0)
        setOnClickListener { onClick() }
    }

    private fun spacer() = View(this).apply { setPadding(0, dp(8), 0, 0) }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
