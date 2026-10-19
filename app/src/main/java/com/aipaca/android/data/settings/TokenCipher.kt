package com.aipaca.android.data.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object TokenCipher {

    const val PREFIX = "enc:v1:"

    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "aipaca_hf_token"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_SEPARATOR = ":"
    private const val TAG_LENGTH_BITS = 128
    private const val KEY_SIZE_BITS = 256

    private val lock = Any()

    @Volatile
    private var cachedKey: SecretKey? = null

    fun encrypt(context: Context, plain: String): String? = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val payload = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        PREFIX + encode(cipher.iv) + IV_SEPARATOR + encode(payload)
    } catch (e: Exception) {
        null
    }

    fun decrypt(context: Context, stored: String): String? {
        if (!stored.startsWith(PREFIX)) return null
        return try {
            val parts = stored.removePrefix(PREFIX).split(IV_SEPARATOR, limit = 2)
            if (parts.size != 2) return null
            val iv = decode(parts[0])
            val payload = decode(parts[1])
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
            String(cipher.doFinal(payload), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    private fun key(): SecretKey {
        cachedKey?.let { return it }
        return synchronized(lock) {
            cachedKey?.let { return it }
            val key = existingKey() ?: generateKey()
            cachedKey = key
            key
        }
    }

    private fun existingKey(): SecretKey? = try {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        keyStore.getKey(ALIAS, null) as? SecretKey
    } catch (e: Exception) {
        null
    }

    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(text: String): ByteArray = Base64.decode(text, Base64.NO_WRAP)
}
