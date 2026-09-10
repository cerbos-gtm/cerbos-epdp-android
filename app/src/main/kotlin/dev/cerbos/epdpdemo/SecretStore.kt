package dev.cerbos.epdpdemo

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Minimal Android Keystore wrapper for the demo's Hub client secret. Credentials must never be
 * hard-coded or stored in plain `SharedPreferences`. The AES key lives in the hardware-backed
 * keystore; only the ciphertext is written to disk.
 */
class SecretStore(context: Context) {
    private val preferences = context.getSharedPreferences("cerbos.secrets", Context.MODE_PRIVATE)

    fun get(key: String): String? {
        val encoded = preferences.getString(key, null) ?: return null
        return try {
            val blob = Base64.decode(encoded, Base64.NO_WRAP)
            val iv = blob.copyOfRange(0, IV_LENGTH)
            val ciphertext = blob.copyOfRange(IV_LENGTH, blob.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (_: GeneralSecurityException) {
            // The key was rotated or the device was restored; the stored value is unusable.
            preferences.edit { remove(key) }
            null
        } catch (_: IllegalArgumentException) {
            preferences.edit { remove(key) }
            null
        }
    }

    fun set(key: String, value: String?) {
        if (value.isNullOrEmpty()) {
            preferences.edit { remove(key) }
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val blob = cipher.iv + ciphertext
        preferences.edit { putString(key, Base64.encodeToString(blob, Base64.NO_WRAP)) }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let {
            return it
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        const val CLIENT_SECRET_KEY = "cerbos.hub.clientSecret"

        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "dev.cerbos.epdpdemo.secrets"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LENGTH = 12
        private const val TAG_BITS = 128
    }
}
