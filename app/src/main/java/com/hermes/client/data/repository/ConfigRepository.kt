package com.hermes.client.data.repository

import com.hermes.client.data.network.EnvVarDto
import com.hermes.client.data.network.HermesRestApi
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class ConfigRepository(private val rest: HermesRestApi) {
    suspend fun get(profile: String? = null): JsonObject = rest.getConfig(profile)

    /**
     * Read the whole config, apply [mutate] to a mutable copy of its top-level map, and write the
     * WHOLE object back. Reading-then-writing the entire object guarantees no fields (including
     * API keys held elsewhere in the config) are dropped.
     */
    suspend fun update(profile: String? = null, mutate: (MutableMap<String, JsonElement>) -> Unit) {
        val current = rest.getConfig(profile)
        val copy = current.toMutableMap()
        mutate(copy)
        rest.putConfig(JsonObject(copy), profile)
    }

    /** Replace a single nested section object (e.g. "memory") wholesale. */
    suspend fun updateSection(section: String, newValue: JsonObject, profile: String? = null) =
        update(profile) { it[section] = newValue }

    fun section(config: JsonObject, name: String): JsonObject? =
        (config[name] as? JsonObject) ?: (config[name])?.let { runCatching { it.jsonObject }.getOrNull() }

    suspend fun setProfileModel(name: String, provider: String, model: String) =
        rest.setProfileModel(name, provider, model)
}

class EnvRepository(private val rest: HermesRestApi) {
    suspend fun vars(profile: String? = null): Map<String, EnvVarDto> = rest.envVars(profile)
    suspend fun set(key: String, value: String, profile: String? = null) = rest.setEnv(key, value, profile)
    suspend fun reveal(key: String, profile: String? = null): String = rest.revealEnv(key, profile)
}
