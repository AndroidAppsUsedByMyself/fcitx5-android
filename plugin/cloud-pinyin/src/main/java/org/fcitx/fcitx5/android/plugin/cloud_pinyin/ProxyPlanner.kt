/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.cloud_pinyin

import java.net.InetSocketAddress
import java.net.Proxy

data class ProxyCredentials(val username: String, val password: String)

/**
 * Concrete proxy decision for one request. Failures are returned instead of throwing so
 * the caller can log them and skip the provider without touching the network.
 */
sealed interface ProxyPlan {
    data object Direct : ProxyPlan

    data class Proxied(
        val proxy: Proxy,
        /** Credentials for the proxy's 407 challenge, if any. */
        val credentials: ProxyCredentials?
    ) : ProxyPlan

    data class Failure(
        val reason: CloudPinyinFailure,
        val detail: String? = null
    ) : ProxyPlan
}

object ProxyPlanner {

    fun resolve(
        config: ProxyConfig,
        credentialStore: ProxyCredentialStore?
    ): ProxyPlan {
        if (!config.enabled) return ProxyPlan.Direct
        if (!config.isValid) {
            return ProxyPlan.Failure(CloudPinyinFailure.ProxyConfigInvalid)
        }
        return when (config.kind) {
            ProxyKind.None -> ProxyPlan.Direct

            ProxyKind.Http -> {
                val address = InetSocketAddress(config.host.trim(), config.port)
                if (!config.hasAuthFields) {
                    ProxyPlan.Proxied(Proxy(Proxy.Type.HTTP, address), null)
                } else {
                    val username = config.username?.takeIf { it.isNotBlank() }
                        ?: return ProxyPlan.Failure(
                            CloudPinyinFailure.ProxyCredentialMissing, "username"
                        )
                    val credentialId = config.credentialId?.takeIf { it.isNotBlank() }
                        ?: return ProxyPlan.Failure(
                            CloudPinyinFailure.ProxyCredentialMissing, "credential"
                        )
                    val password = credentialStore?.load(credentialId)
                        ?: return ProxyPlan.Failure(
                            CloudPinyinFailure.ProxyCredentialMissing, "credential"
                        )
                    ProxyPlan.Proxied(
                        Proxy(Proxy.Type.HTTP, address),
                        ProxyCredentials(username, password)
                    )
                }
            }

            ProxyKind.Socks5 -> {
                if (config.hasAuthFields) {
                    // Reserved for a future implementation; must not silently degrade.
                    ProxyPlan.Failure(CloudPinyinFailure.ProxyAuthUnsupported)
                } else {
                    ProxyPlan.Proxied(
                        Proxy(
                            Proxy.Type.SOCKS,
                            InetSocketAddress(config.host.trim(), config.port)
                        ),
                        null
                    )
                }
            }
        }
    }
}
