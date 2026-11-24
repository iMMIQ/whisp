// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecretStoreException(message: String, cause: Throwable? = null) : IOException(message, cause)

class SecretStore {
    private val alias = "whisp-source-credentials"
    private var cachedKey: SecretKey? = null
    private val cache =
        object : LinkedHashMap<String, String>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, String>): Boolean = size > 32
        }

    @Synchronized
    private fun key(forceReload: Boolean = false): SecretKey {
        if (!forceReload)
            cachedKey?.let {
                return it
            }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let {
            cachedKey = it
            return it
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                            alias,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build(),
                )
            }
            .generateKey()
            .also { cachedKey = it }
    }

    fun encrypt(value: String): String {
        if (value.isEmpty()) return ""
        return try {
            encryptWith(value, key())
        } catch (_: GeneralSecurityException) {
            try {
                encryptWith(value, key(forceReload = true))
            } catch (e: GeneralSecurityException) {
                throw SecretStoreException("无法加密保存来源凭据", e)
            }
        }
    }

    private fun encryptWith(value: String, secretKey: SecretKey): String {
        val cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secretKey) }
        val bytes = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    fun decrypt(value: String): String {
        if (value.isEmpty()) return ""
        val bytes =
            try {
                Base64.decode(value, Base64.NO_WRAP)
            } catch (e: IllegalArgumentException) {
                throw SecretStoreException("来源凭据已损坏，请重新配置或登录", e)
            }
        // AES-GCM requires a 12-byte IV plus at least a 16-byte authentication tag.
        if (bytes.size < 28) throw SecretStoreException("来源凭据已损坏，请重新配置或登录")
        return try {
            decryptWith(bytes, key())
        } catch (_: GeneralSecurityException) {
            try {
                decryptWith(bytes, key(forceReload = true))
            } catch (e: GeneralSecurityException) {
                throw SecretStoreException("来源凭据已失效，请重新配置或登录", e)
            }
        }
    }

    private fun decryptWith(bytes: ByteArray, secretKey: SecretKey): String =
        Cipher.getInstance("AES/GCM/NoPadding")
            .apply {
                init(
                    Cipher.DECRYPT_MODE,
                    secretKey,
                    GCMParameterSpec(128, bytes.copyOfRange(0, 12)),
                )
            }
            .doFinal(bytes.copyOfRange(12, bytes.size))
            .toString(Charsets.UTF_8)

    /** Key carries the ciphertext so password rotations never return stale entries. */
    fun decryptCached(source: SourceEntity): String =
        synchronized(cache) {
            cache.getOrPut("${source.id}|${source.secret}") { decrypt(source.secret) }
        }

    fun evict(sourceId: String) {
        val prefix = "$sourceId|"
        synchronized(cache) { cache.keys.removeAll { it.startsWith(prefix) } }
    }
}
