package com.hermes.client.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.modelFavoritesDataStore by preferencesDataStore(name = "model_favorites")

/**
 * Stable key for a (provider, model) pair. NUL (`\u0000`) separator: provider slugs and model
 * names can contain ':', '/', '(', ')', '.', '-', and spaces, but never NUL — so the key never
 * collides across different (provider, model) splits.
 */
fun favKey(provider: String, model: String): String = "$provider\u0000$model"

/** Device-local, global (not per-profile) set of starred favorite models. */
class ModelFavoritesStore(private val context: Context) {
    private val key = stringSetPreferencesKey("favorites")

    val favorites: Flow<Set<String>> =
        context.modelFavoritesDataStore.data.map { it[key] ?: emptySet() }

    suspend fun toggle(provider: String, model: String) {
        val k = favKey(provider, model)
        context.modelFavoritesDataStore.edit { prefs ->
            val cur = prefs[key] ?: emptySet()
            prefs[key] = if (k in cur) cur - k else cur + k
        }
    }
}
