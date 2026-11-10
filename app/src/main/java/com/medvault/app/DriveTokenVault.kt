package com.medvault.app

import android.content.Context
import android.util.Base64
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object DriveTokenVault {

    private const val KEY_ALIAS = "medvault_drive_token_key"
    private const val PREFS = "medvault_secure_storage"
    private const val TOKEN_KEY = "drive_access_token"

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12
    private const val TAG_LENGTH = 128

    private fun getKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)

        val existingKey = keyStore.getKey(KEY_ALIAS, null)
        if (existingKey is SecretKey) {
            return existingKey
        }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )

        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or
                KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(
                KeyProperties.ENCRYPTION_PADDING_NONE
            )
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()

        generator.init(spec)
        return generator.generateKey()
    }

    /**
     * Encrypt and save the Google Drive access token.
     */
    @Synchronized
    fun save(context: Context, token: String) {
        require(token.isNotBlank()) {
            "Access token cannot be empty."
        }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getKey())

        val encrypted = cipher.doFinal(
            token.toByteArray(Charsets.UTF_8)
        )

        val stored = Base64.encodeToString(
            cipher.iv + encrypted,
            Base64.NO_WRAP
        )

        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(TOKEN_KEY, stored)
            .apply()
    }

    /**
     * Decrypt and retrieve the saved access token.
     */
    @Synchronized
    fun get(context: Context): String? {
        val preferences = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        val stored = preferences.getString(TOKEN_KEY, null)
            ?: return null

        return try {
            val combined = Base64.decode(
                stored,
                Base64.NO_WRAP
            )

            if (combined.size <= IV_LENGTH) {
                return null
            }

            val iv = combined.copyOfRange(0, IV_LENGTH)
            val encrypted = combined.copyOfRange(
                IV_LENGTH,
                combined.size
            )

            val cipher = Cipher.getInstance(TRANSFORMATION)

            cipher.init(
                Cipher.DECRYPT_MODE,
                getKey(),
                GCMParameterSpec(TAG_LENGTH, iv)
            )

            String(
                cipher.doFinal(encrypted),
                Charsets.UTF_8
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Remove the saved token.
     */
    @Synchronized
    fun clear(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(TOKEN_KEY)
            .apply()
    }
}
