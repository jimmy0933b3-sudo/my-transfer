package com.example.cameratranslator.translation

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Manually entered Worker token encrypted by non-exportable Android Keystore key. */
class CloudCredentialStore(context: Context) {
    private val preferences = context.getSharedPreferences("translator_cloud_auth", Context.MODE_PRIVATE)
    private val alias = "camera_translator_cloud_secret_v1"

    private fun key(): SecretKey {
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keystore.getKey(alias, null) as? SecretKey
        if (existing != null) return existing
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    }

    fun save(token: String): Boolean {
        if (token.length < 32 || token.length > 256 || token.any { it.isWhitespace() }) return false
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, key())
            }
            val bytes = cipher.doFinal(token.toByteArray(StandardCharsets.UTF_8))
            preferences.edit()
                .putString("token_data", Base64.encodeToString(bytes, Base64.NO_WRAP))
                .putString("token_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .apply()
            true
        } catch (_: Exception) { false }
    }

    fun load(): String? {
        return try {
            val data = preferences.getString("token_data", null) ?: return null
            val iv = preferences.getString("token_iv", null) ?: return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(
                    Cipher.DECRYPT_MODE, key(),
                    GCMParameterSpec(128, Base64.decode(iv, Base64.DEFAULT))
                )
            }
            String(
                cipher.doFinal(Base64.decode(data, Base64.DEFAULT)),
                StandardCharsets.UTF_8
            )
        } catch (_: Exception) { null }
    }

    fun clear() {
        preferences.edit().remove("token_data").remove("token_iv").apply()
    }
}
