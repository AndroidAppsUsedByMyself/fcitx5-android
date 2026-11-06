/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.cloud_pinyin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Proxy

class ProxyPlannerTest {

    private class FakeStore(
        private val values: Map<String, String> = emptyMap()
    ) : ProxyCredentialStore {
        val removed = mutableListOf<String>()
        override fun store(id: String, password: String): Boolean = false
        override fun load(id: String): String? = values[id]
        override fun remove(id: String) {
            removed += id
        }
    }

    @Test
    fun testDirectIsNoProxy() {
        assertEquals(ProxyPlan.Direct, ProxyPlanner.resolve(ProxyConfig(), null))
    }

    @Test
    fun testHttpWithoutAuth() {
        val plan = ProxyPlanner.resolve(
            ProxyConfig(ProxyKind.Http, "proxy.example.com", 8080), null
        )
        assertTrue(plan is ProxyPlan.Proxied)
        plan as ProxyPlan.Proxied
        assertEquals(Proxy.Type.HTTP, plan.proxy.type())
        assertNull(plan.credentials)
    }

    @Test
    fun testHttpWithBasicAuth() {
        val plan = ProxyPlanner.resolve(
            ProxyConfig(ProxyKind.Http, "proxy.example.com", 8080, "user", "id-1"),
            FakeStore(mapOf("id-1" to "pass"))
        )
        assertTrue(plan is ProxyPlan.Proxied)
        assertEquals(
            ProxyCredentials("user", "pass"),
            (plan as ProxyPlan.Proxied).credentials
        )
    }

    @Test
    fun testMissingCredentialRefusesRequest() {
        val plan = ProxyPlanner.resolve(
            ProxyConfig(ProxyKind.Http, "proxy.example.com", 8080, "user", "id-1"),
            FakeStore()
        )
        assertEquals(
            CloudPinyinFailure.ProxyCredentialMissing,
            (plan as ProxyPlan.Failure).reason
        )
    }

    @Test
    fun testSocksWithoutAuth() {
        val plan = ProxyPlanner.resolve(
            ProxyConfig(ProxyKind.Socks5, "127.0.0.1", 1080), null
        )
        assertTrue(plan is ProxyPlan.Proxied)
        assertEquals(
            Proxy.Type.SOCKS,
            (plan as ProxyPlan.Proxied).proxy.type()
        )
        assertNull(plan.credentials)
    }

    @Test
    fun testSocksWithAuthIsExplicitlyUnsupported() {
        val plan = ProxyPlanner.resolve(
            ProxyConfig(
                ProxyKind.Socks5, "127.0.0.1", 1080, "user", "id-1"
            ),
            FakeStore(mapOf("id-1" to "pass"))
        )
        assertEquals(
            CloudPinyinFailure.ProxyAuthUnsupported,
            (plan as ProxyPlan.Failure).reason
        )
    }

    @Test
    fun testInvalidProxyConfig() {
        assertEquals(
            CloudPinyinFailure.ProxyConfigInvalid,
            (ProxyPlanner.resolve(
                ProxyConfig(ProxyKind.Http, "", 8080), null
            ) as ProxyPlan.Failure).reason
        )
        assertEquals(
            CloudPinyinFailure.ProxyConfigInvalid,
            (ProxyPlanner.resolve(
                ProxyConfig(ProxyKind.Http, "host", 0), null
            ) as ProxyPlan.Failure).reason
        )
    }
}
