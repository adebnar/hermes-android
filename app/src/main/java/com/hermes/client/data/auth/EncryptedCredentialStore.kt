package com.hermes.client.data.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * Concrete secure storage backed by EncryptedSharedPreferences (security-crypto 1.0.0).
 * If migrating to a newer backend later, only this file changes — callers depend on
 * the CredentialStore interface.
 */
class EncryptedCredentialStore(context: Context) : CredentialStore {
    private val prefs by lazy {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            "hermes_credentials",
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun load(): GatewayConfig? {
        val url = prefs.getString("base_url", null) ?: return null
        return GatewayConfig(
            baseUrl = url,
            token = prefs.getString("token", null) ?: "",
            username = prefs.getString("username", null) ?: "",
            password = prefs.getString("password", null) ?: "",
        )
    }

    override fun save(config: GatewayConfig) {
        prefs.edit()
            .putString("base_url", config.baseUrl)
            .putString("token", config.token)
            .putString("username", config.username)
            .putString("password", config.password)
            .apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }
}
