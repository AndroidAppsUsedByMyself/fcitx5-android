/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.cloud_pinyin

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * AES-256-GCM credential store backed by the Android Keystore.
 *
 * The ciphertext and IV live in a private SharedPreferences file. The key never leaves
 * the Keystore, so a restored backup (or a reset Keystore) makes [load] return null and
 * the caller refuses to send the request instead of silently connecting directly.
 */
class KeystoreProxyCredentialStore(context: Context) : ProxyCredentialStore {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    override fun store(id: String, password: String): Boolean = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        val value = encode(cipher.iv) + ":" + encode(encrypted)
        prefs.edit().putString(id, value).commit()
    }.getOrDefault(false)

    @Synchronized
    override fun load(id: String): String? = runCatching {
        val raw = prefs.getString(id, null) ?: return null
        val separator = raw.indexOf(':')
        if (separator <= 0) return null
        val iv = decode(raw.substring(0, separator))
        val encrypted = decode(raw.substring(separator + 1))
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), javax.crypto.spec.GCMParameterSpec(128, iv))
        String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }.getOrNull()

    @Synchronized
    override fun remove(id: String) {
        prefs.edit().remove(id).apply()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun encode(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(value: String): ByteArray =
        Base64.decode(value, Base64.NO_WRAP)

    private companion object {
        const val PREFS = "cloud_pinyin_secrets"
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "cloud_pinyin_proxy_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
