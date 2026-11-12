package com.medvault.app

import android.content.Context
import android.util.Base64
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object DriveTokenVault {
    private const val KEY_ALIAS = "medvault_drive_token_key"
    private const val PREFS = "medvault_secure_storage"
    private const val LEGACY_TOKEN_KEY = "drive_access_token"
    private const val ACCOUNT_LIST_KEY = "drive_accounts"
    private const val ACTIVE_ACCOUNT_KEY = "active_drive_account"
    private const val TOKEN_PREFIX = "drive_access_token_"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12
    private const val TAG_LENGTH = 128

    private fun getKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        val existing = keyStore.getKey(KEY_ALIAS, null)
        if (existing is SecretKey) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun tokenKey(accountId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(accountId.trim().lowercase().toByteArray(Charsets.UTF_8))
        val suffix = digest.joinToString("") { "%02x".format(it) }
        return TOKEN_PREFIX + suffix
    }

    @Synchronized
    fun save(context: Context, token: String) = save(context, "default", token)

    @Synchronized
    fun save(context: Context, accountId: String, token: String) {
        require(accountId.isNotBlank() && token.isNotBlank())
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getKey())
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val stored = Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(tokenKey(accountId), stored).apply()
    }

    @Synchronized
    fun get(context: Context): String? =
        get(context, getActiveAccountId(context) ?: "default")

    @Synchronized
    fun get(context: Context, accountId: String): String? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(tokenKey(accountId), null)
            ?: if (accountId == "default") prefs.getString(LEGACY_TOKEN_KEY, null) else null
            ?: return null
        return try {
            val combined = Base64.decode(stored, Base64.NO_WRAP)
            if (combined.size <= IV_LENGTH) return null
            val iv = combined.copyOfRange(0, IV_LENGTH)
            val encrypted = combined.copyOfRange(IV_LENGTH, combined.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getKey(), GCMParameterSpec(TAG_LENGTH, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun clear(context: Context) = clear(context, getActiveAccountId(context) ?: "default")

    @Synchronized
    fun clear(context: Context, accountId: String) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(tokenKey(accountId)).apply()
    }

    fun saveAccount(context: Context, account: DriveAccount) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val list = accounts(context).filterNot { it.id.equals(account.id, true) }.toMutableList()
        list.add(account)
        val array = JSONArray()
        list.forEach { array.put(JSONObject().put("id", it.id).put("email", it.email).put("displayName", it.displayName)) }
        prefs.edit().putString(ACCOUNT_LIST_KEY, array.toString()).apply()
    }

    fun accounts(context: Context): List<DriveAccount> {
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(ACCOUNT_LIST_KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                val id = obj.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val email = obj.optString("email", id)
                DriveAccount(id, email, obj.optString("displayName", email))
            }
        } catch (_: Exception) { emptyList() }
    }

    fun setActiveAccountId(context: Context, accountId: String) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(ACTIVE_ACCOUNT_KEY, accountId).apply()
    }

    fun getActiveAccountId(context: Context): String? =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(ACTIVE_ACCOUNT_KEY, null)

    fun removeAccount(context: Context, accountId: String) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val list = accounts(context).filterNot { it.id.equals(accountId, true) }
        val array = JSONArray()
        list.forEach { array.put(JSONObject().put("id", it.id).put("email", it.email).put("displayName", it.displayName)) }
        prefs.edit().putString(ACCOUNT_LIST_KEY, array.toString()).remove(tokenKey(accountId)).apply()
        if (getActiveAccountId(context).equals(accountId, true)) {
            prefs.edit().putString(ACTIVE_ACCOUNT_KEY, list.firstOrNull()?.id).apply()
        }
    }
}
