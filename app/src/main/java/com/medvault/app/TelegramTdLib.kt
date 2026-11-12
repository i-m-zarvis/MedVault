package com.medvault.app

import android.util.Log
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi

/**
 * Small boundary around the official TDLib Java/JNI client.
 *
 * This deliberately does not start a Telegram session yet. Authentication,
 * encrypted database parameters, and lifecycle ownership will be added in
 * the next integration step after the native runtime is verified in CI.
 */
object TelegramTdLib {
    private const val TAG = "MedVault-TDLib"

    @Synchronized
    fun createClient(
        updateHandler: (TdApi.Object) -> Unit
    ): Client {
        // Load explicitly so a missing or incorrectly packaged ABI fails here
        // with a useful error instead of failing later during a native call.
        System.loadLibrary("tdjni")

        return Client.create(
            { update -> updateHandler(update) },
            { error -> Log.e(TAG, "TDLib update callback failed", error) },
            { error -> Log.e(TAG, "TDLib callback failed", error) }
        )
    }
}
