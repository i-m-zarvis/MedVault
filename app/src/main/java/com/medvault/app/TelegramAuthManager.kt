package com.medvault.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Owns one TDLib client and the Telegram authorization lifecycle.
 *
 * The TDLib database encryption key is encrypted at rest with an Android
 * Keystore AES-GCM key. Telegram account credentials are never sent to MedVault.
 */
class TelegramAuthManager private constructor(
    context: Context,
    @Volatile private var listener: (State) -> Unit
) {
    sealed class State {
        data object Starting : State()
        data object WaitingForPhone : State()
        data object WaitingForCode : State()
        data object WaitingForPassword : State()
        data class Ready(val userId: Long) : State()
        data class Failed(val message: String) : State()
        data object Closed : State()
    }

    companion object {
        @Volatile private var sharedInstance: TelegramAuthManager? = null

        fun getInstance(context: Context, listener: (State) -> Unit): TelegramAuthManager =
            synchronized(this) {
                val existing = sharedInstance
                if (existing != null) {
                    existing.listener = listener
                    existing
                } else {
                    TelegramAuthManager(context.applicationContext, listener).also {
                        sharedInstance = it
                    }
                }
            }
    }

    @Volatile var currentState: State = State.Starting
        private set

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("telegram_tdlib", Context.MODE_PRIVATE)
    private val databaseDirectory = appContext.getDir("telegram-db", Context.MODE_PRIVATE).absolutePath
    private val filesDirectory = appContext.getDir("telegram-files", Context.MODE_PRIVATE).absolutePath
    @Volatile private var client: Client? = null
    private val updateListeners = CopyOnWriteArrayList<(TdApi.Object) -> Unit>()

    fun addUpdateListener(listener: (TdApi.Object) -> Unit) { updateListeners.add(listener) }
    fun removeUpdateListener(listener: (TdApi.Object) -> Unit) { updateListeners.remove(listener) }

    fun sendRequest(request: TdApi.Function<*>, callback: (TdApi.Object) -> Unit) {
        send(request, callback)
    }

    @Synchronized
    fun start() {
        // The manager is process-wide. Navigating away from Settings must not
        // create another TDLib client against the database already in use.
        val existingClient = client
        if (existingClient != null) {
            publish(currentState)
            return
        }
        if (BuildConfig.TELEGRAM_API_ID <= 0 || BuildConfig.TELEGRAM_API_HASH.isBlank()) {
            publish(State.Failed("Telegram API credentials are missing from this build."))
            return
        }
        publish(State.Starting)
        try {
            val tdClient = TelegramTdLib.createClient(::onUpdate)
            client = tdClient
            // Parameters are sent when TDLib emits WaitTdlibParameters.
        } catch (error: Exception) {
            publish(State.Failed(error.message ?: "Could not start Telegram."))
        }
    }

    fun submitPhoneNumber(phoneNumber: String) {
        val normalized = phoneNumber.trim()
        if (!normalized.matches(Regex("^\\+[1-9][0-9]{7,14}$"))) {
            publish(State.Failed("Enter a valid phone number with country code, e.g. +919876543210."))
            return
        }
        send(TdApi.SetAuthenticationPhoneNumber(normalized, null)) { result ->
            if (result is TdApi.Error) publish(State.Failed(result.message))
        }
    }

    fun submitCode(code: String) {
        val normalized = code.trim()
        if (normalized.isBlank()) {
            publish(State.Failed("Enter the Telegram login code."))
            return
        }
        send(TdApi.CheckAuthenticationCode(normalized)) { result ->
            if (result is TdApi.Error) publish(State.Failed(result.message))
        }
    }

    fun submitPassword(password: String) {
        if (password.isBlank()) {
            publish(State.Failed("Enter your Telegram two-step verification password."))
            return
        }
        send(TdApi.CheckAuthenticationPassword(password)) { result ->
            if (result is TdApi.Error) publish(State.Failed(result.message))
        }
    }

    fun close() {
        val active = client
        client = null
        if (active != null) {
            active.send(TdApi.Close()) {
                publish(State.Closed)
            }
        } else {
            publish(State.Closed)
        }
    }

    private fun send(request: TdApi.Function<*>, callback: (TdApi.Object) -> Unit = {}) {
        val active = client
        if (active == null) {
            publish(State.Failed("Telegram client is not running."))
            return
        }
        active.send(request) { result ->
            if (result is TdApi.Error) {
                publish(State.Failed(result.message))
            } else {
                callback(result)
            }
        }
    }

    private fun sendTdlibParameters() {
        val parameters = TdApi.SetTdlibParameters(
            false, databaseDirectory, filesDirectory, databaseEncryptionKey(),
            true, true, true, false,
            BuildConfig.TELEGRAM_API_ID, BuildConfig.TELEGRAM_API_HASH,
            "en", android.os.Build.MODEL ?: "Android",
            android.os.Build.VERSION.RELEASE ?: "Android", "1.0"
        )
        send(parameters)
    }
    private fun onUpdate(update: TdApi.Object) {
        updateListeners.forEach { listener -> runCatching { listener(update) } }
        if (update !is TdApi.UpdateAuthorizationState) return
        when (val auth = update.authorizationState) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> sendTdlibParameters()
            is TdApi.AuthorizationStateWaitPhoneNumber -> publish(State.WaitingForPhone)
            is TdApi.AuthorizationStateWaitCode -> publish(State.WaitingForCode)
            is TdApi.AuthorizationStateWaitPassword -> publish(State.WaitingForPassword)
            is TdApi.AuthorizationStateReady -> {
                send(TdApi.GetMe()) { result ->
                    if (result is TdApi.User) publish(State.Ready(result.id))
                }
            }
            is TdApi.AuthorizationStateLoggingOut -> publish(State.Starting)
            is TdApi.AuthorizationStateClosing -> publish(State.Starting)
            is TdApi.AuthorizationStateClosed -> publish(State.Closed)
        }
    }

    private fun databaseEncryptionKey(): ByteArray {
        val encrypted = prefs.getString("database_key", null)
        if (encrypted != null) {
            val packed = Base64.decode(encrypted, Base64.NO_WRAP)
            val buffer = ByteBuffer.wrap(packed)
            val nonceSize = buffer.int
            val nonce = ByteArray(nonceSize).also(buffer::get)
            val ciphertext = ByteArray(buffer.remaining()).also(buffer::get)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), javax.crypto.spec.GCMParameterSpec(128, nonce))
            return cipher.doFinal(ciphertext)
        }

        val raw = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        val encryptedBytes = cipher.doFinal(raw)
        val nonce = cipher.iv
        val packed = ByteBuffer.allocate(4 + nonce.size + encryptedBytes.size)
            .putInt(nonce.size).put(nonce).put(encryptedBytes).array()
        prefs.edit().putString("database_key", Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
        return raw
    }

    private fun keystoreKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "MedVaultTelegramDatabaseKey"
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun publish(state: State) {
        currentState = state
        listener(state)
    }
}
