/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.cloud_pinyin

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.Window
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.UUID

/**
 * Ordered provider list, fallback switch and provider management.
 */
class CloudPinyinSettingsActivity : Activity() {

    private lateinit var content: LinearLayout

    private val credentialStore by lazy { KeystoreProxyCredentialStore(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        val scroll = ScrollView(this).apply {
            // Keep content clear of the status bar under edge-to-edge enforcement.
            fitsSystemWindows = true
        }
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

        content.addView(heading(getString(R.string.settings_title)))
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
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@CloudPinyinSettingsActivity).apply {
                text = getString(entry.provider.labelRes)
                setTypeface(Typeface.DEFAULT_BOLD)
            })
            val subtitle = if (entry.provider == CloudPinyinProvider.Custom) {
                entry.customUrl
            } else {
                entry.provider.endpointLabel
            }
            subtitle?.let {
                addView(TextView(this@CloudPinyinSettingsActivity).apply {
                    text = it
                    textSize = 12f
                    alpha = 0.7f
                })
            }
        }
        row.addView(labels)
        if (entry.provider == CloudPinyinProvider.Custom) {
            row.addView(smallButton(getString(R.string.settings_custom_edit), true) {
                showCustomUrlDialog(entry.customUrl) { url ->
                    val updated = all.toMutableList().apply {
                        set(index, ProviderEntry(CloudPinyinProvider.Custom, url, entry.proxy))
                    }
                    CloudPinyinSettings.setProviders(this, updated)
                    render()
                }
            })
        }
        row.addView(smallButton(getString(R.string.settings_proxy), true) {
            showProxyDialog(entry, all, index)
        })
        row.addView(smallButton(getString(R.string.settings_provider_up), index > 0) {
            move(index, -1, all)
        })
        row.addView(smallButton(getString(R.string.settings_provider_down), index < all.lastIndex) {
            move(index, +1, all)
        })
        row.addView(smallButton(getString(R.string.settings_provider_remove), true) {
            entry.proxy.credentialId?.let(credentialStore::remove)
            val updated = all.filterIndexed { i, _ -> i != index }
            CloudPinyinSettings.setProviders(this, updated)
            render()
        })
        outer.addView(row)
        outer.addView(TextView(this).apply {
            text = proxySummary(entry)
            textSize = 12f
            alpha = 0.7f
            setPadding(0, dp(2), 0, 0)
        })
        return outer
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
                if (provider == CloudPinyinProvider.Custom) {
                    showCustomUrlDialog(null) { url ->
                        if (existing.any {
                                it.provider == CloudPinyinProvider.Custom && it.customUrl == url
                            }
                        ) {
                            Toast.makeText(
                                this,
                                R.string.settings_provider_duplicate,
                                Toast.LENGTH_SHORT
                            ).show()
                            return@showCustomUrlDialog
                        }
                        CloudPinyinSettings.setProviders(
                            this,
                            existing + ProviderEntry(CloudPinyinProvider.Custom, url)
                        )
                        render()
                    }
                    return@setItems
                }
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

    private fun showCustomUrlDialog(existing: String?, onAccept: (String) -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            setText(existing.orEmpty())
            setSelection(text.length)
            hint = "https://example.com/cloud"
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.settings_custom_url_title)
            .setMessage(R.string.settings_custom_url_contract)
            .setView(input)
            .setPositiveButton(R.string.settings_save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val url = CustomEndpointUrl.normalize(input.text.toString())
                if (url == null) {
                    input.error = getString(R.string.settings_custom_url_invalid)
                    return@setOnClickListener
                }
                onAccept(url)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun proxySummary(entry: ProviderEntry): String {
        val proxy = entry.proxy
        if (!proxy.enabled) return getString(R.string.settings_proxy_summary_direct)
        if (!proxy.isValid) return getString(R.string.settings_proxy_summary_invalid)
        val base = when (proxy.kind) {
            ProxyKind.Http -> getString(
                R.string.settings_proxy_summary_http, proxy.host, proxy.port
            )
            ProxyKind.Socks5 -> getString(
                R.string.settings_proxy_summary_socks5, proxy.host, proxy.port
            )
            ProxyKind.None -> getString(R.string.settings_proxy_summary_direct)
        }
        return when {
            proxy.kind == ProxyKind.Socks5 && proxy.hasAuthFields ->
                getString(R.string.settings_proxy_summary_unsupported, base)
            proxy.hasAuthFields &&
                credentialStore.load(proxy.credentialId.orEmpty()) == null ->
                getString(R.string.settings_proxy_summary_missing, base)
            proxy.hasAuthFields -> getString(R.string.settings_proxy_summary_auth, base)
            else -> base
        }
    }

    private fun showProxyDialog(entry: ProviderEntry, all: List<ProviderEntry>, index: Int) {
        val existing = entry.proxy
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val direct = RadioButton(this).apply {
            id = View.generateViewId()
            text = getString(R.string.settings_proxy_direct)
        }
        val http = RadioButton(this).apply {
            id = View.generateViewId()
            text = getString(R.string.settings_proxy_http)
        }
        val socks = RadioButton(this).apply {
            id = View.generateViewId()
            text = getString(R.string.settings_proxy_socks5)
        }
        group.addView(direct)
        group.addView(http)
        group.addView(socks)
        container.addView(group)

        val host = input(getString(R.string.settings_proxy_host)).apply {
            setText(existing.host)
        }
        val port = input(getString(R.string.settings_proxy_port), InputType.TYPE_CLASS_NUMBER)
            .apply {
                if (existing.port > 0) setText(existing.port.toString())
            }
        val username = input(getString(R.string.settings_proxy_username)).apply {
            setText(existing.username.orEmpty())
        }
        val password = input(
            getString(R.string.settings_proxy_password),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        ).apply {
            if (existing.credentialId != null) {
                hint = getString(R.string.settings_proxy_password_keep)
            }
        }
        val authNote = body(getString(R.string.settings_proxy_auth_unsupported)).apply {
            alpha = 0.7f
            visibility = View.GONE
        }
        container.addView(host)
        container.addView(port)
        container.addView(username)
        container.addView(password)
        container.addView(authNote)

        val kindForId = mapOf(
            direct.id to ProxyKind.None,
            http.id to ProxyKind.Http,
            socks.id to ProxyKind.Socks5
        )
        val idForKind = kindForId.entries.associate { (id, kind) -> kind to id }
        group.check(idForKind[existing.kind] ?: direct.id)
        fun updateFields(kind: ProxyKind) {
            val addressVisible = if (kind == ProxyKind.None) View.GONE else View.VISIBLE
            val authVisible = if (kind == ProxyKind.Http) View.VISIBLE else View.GONE
            host.visibility = addressVisible
            port.visibility = addressVisible
            username.visibility = authVisible
            password.visibility = authVisible
            authNote.visibility = if (kind == ProxyKind.Socks5) View.VISIBLE else View.GONE
        }
        updateFields(existing.kind)
        group.setOnCheckedChangeListener { _, checkedId ->
            updateFields(kindForId[checkedId] ?: ProxyKind.None)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings_proxy_title, getString(entry.provider.labelRes)))
            .setView(ScrollView(this).apply { addView(container) })
            .setPositiveButton(R.string.settings_save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val kind = kindForId[group.checkedRadioButtonId] ?: ProxyKind.None
                val updated = proxyFromFields(entry, kind, host, port, username, password)
                    ?: return@setOnClickListener
                val oldCredential = existing.credentialId
                if (oldCredential != null && oldCredential != updated.credentialId) {
                    credentialStore.remove(oldCredential)
                }
                val updatedEntries = all.toMutableList().apply {
                    set(index, ProviderEntry(entry.provider, entry.customUrl, updated))
                }
                CloudPinyinSettings.setProviders(this, updatedEntries)
                render()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    /**
     * Returns the proxy to persist, or null when validation failed and the dialog should
     * stay open. Never falls back to a direct connection when a credential cannot be
     * stored.
     */
    private fun proxyFromFields(
        entry: ProviderEntry,
        kind: ProxyKind,
        hostField: EditText,
        portField: EditText,
        usernameField: EditText,
        passwordField: EditText
    ): ProxyConfig? {
        if (kind == ProxyKind.None) return ProxyConfig()
        val host = hostField.text.toString().trim()
        if (host.isEmpty()) {
            hostField.error = getString(R.string.settings_proxy_host_required)
            hostField.requestFocus()
            return null
        }
        val port = portField.text.toString().toIntOrNull()
        if (port == null || port !in 1..65535) {
            portField.error = getString(R.string.settings_proxy_port_invalid)
            portField.requestFocus()
            return null
        }
        if (kind == ProxyKind.Socks5) {
            // Authentication fields are reserved for a future implementation and are
            // cleared only by this explicit save action.
            return ProxyConfig(kind, host, port)
        }
        val username = usernameField.text.toString().trim()
        val password = passwordField.text.toString()
        if (':' in username) {
            usernameField.error = getString(R.string.settings_proxy_username_invalid)
            usernameField.requestFocus()
            return null
        }
        val current = entry.proxy.takeIf { it.kind == ProxyKind.Http }
        return when {
            username.isEmpty() && password.isEmpty() -> ProxyConfig(kind, host, port)

            username.isEmpty() -> {
                passwordField.error = getString(R.string.settings_proxy_auth_pair)
                passwordField.requestFocus()
                null
            }

            password.isEmpty() -> {
                if (current?.hasAuthFields == true &&
                    current.username == username &&
                    current.credentialId != null
                ) {
                    // Keep the existing encrypted password.
                    ProxyConfig(kind, host, port, username, current.credentialId)
                } else {
                    passwordField.error = getString(R.string.settings_proxy_password_required)
                    passwordField.requestFocus()
                    null
                }
            }

            else -> {
                val credentialId = UUID.randomUUID().toString()
                if (!credentialStore.store(credentialId, password)) {
                    Toast.makeText(
                        this,
                        R.string.settings_proxy_credential_save_failed,
                        Toast.LENGTH_SHORT
                    ).show()
                    null
                } else {
                    ProxyConfig(kind, host, port, username, credentialId)
                }
            }
        }
    }

    private fun input(hint: String, inputType: Int = InputType.TYPE_CLASS_TEXT): EditText =
        EditText(this).apply {
            this.hint = hint
            this.inputType = inputType
            setSingleLine()
            setPadding(0, dp(8), 0, dp(8))
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
