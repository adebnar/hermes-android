package com.hermes.client.data.network

import com.hermes.client.data.auth.GatewayConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class HermesApiException(val code: Int, message: String) : Exception(message)

class HermesRestApi(
    private val okHttp: OkHttpClient,
    private val json: Json,
    private val configProvider: () -> GatewayConfig?,
) {
    private fun config(): GatewayConfig =
        configProvider() ?: throw HermesApiException(0, "no gateway configured")

    private fun builder(path: String): Request.Builder {
        val cfg = config()
        // Trim trailing slashes so a user-entered "http://host:9119/" doesn't produce
        // "//api/..." — the gateway routes a double slash to its web UI (HTML), not the API.
        val b = Request.Builder().url("${cfg.baseUrl.trimEnd('/')}$path")
        if (cfg.token.isNotBlank()) b.header("X-Hermes-Session-Token", cfg.token)
        return b
    }

    private suspend inline fun <reified T> get(path: String): T = withContext(Dispatchers.IO) {
        okHttp.newCall(builder(path).get().build()).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw HermesApiException(resp.code, body.ifBlank { "HTTP ${resp.code}" })
            json.decodeFromString<T>(body)
        }
    }

    /**
     * T10b: test connectivity using explicitly supplied credentials WITHOUT reading from
     * configProvider. Used by SetupViewModel.test() so unverified creds are never persisted.
     */
    suspend fun statusFor(baseUrl: String, token: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val rb = Request.Builder().url("${baseUrl.trimEnd('/')}/api/status").get()
            if (token.isNotBlank()) rb.header("X-Hermes-Session-Token", token)
            okHttp.newCall(rb.build()).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    /** Delegates to [statusFor] using the current stored config. */
    suspend fun status(): Boolean {
        val cfg = configProvider() ?: return false
        return statusFor(cfg.baseUrl, cfg.token)
    }

    suspend fun sessions(limit: Int, offset: Int): List<SessionDto> =
        get<SessionListDto>("/api/sessions?limit=$limit&offset=$offset&order=recent").sessions

    suspend fun messages(sessionId: String): List<MessageDto> =
        get<MessagesDto>("/api/sessions/$sessionId/messages").messages

    suspend fun profiles(): List<ProfileDto> = get<ProfilesDto>("/api/profiles").profiles

    suspend fun modelOptions(): List<ModelOptionDto> =
        get<ModelOptionsDto>("/api/model/options").providers.flatMap { p ->
            p.models.map { m -> ModelOptionDto(provider = p.slug, model = m, label = m) }
        }

    suspend fun setModel(provider: String, model: String) = withContext(Dispatchers.IO) {
        val obj: JsonObject = buildJsonObject {
            put("scope", "main")   // required by /api/model/set (the primary model slot)
            put("provider", provider)
            put("model", model)
        }
        val payload = json.encodeToString(JsonObject.serializer(), obj)
            .toRequestBody("application/json".toMediaType())
        okHttp.newCall(builder("/api/model/set").post(payload).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw HermesApiException(resp.code, "set model failed")
        }
    }

    /** Rename and/or archive a session via PATCH /api/sessions/{id}. */
    suspend fun patchSession(
        sessionId: String,
        title: String? = null,
        archived: Boolean? = null,
    ) = withContext(Dispatchers.IO) {
        val obj: JsonObject = buildJsonObject {
            if (title != null) put("title", title)
            if (archived != null) put("archived", archived)
        }
        val payload = json.encodeToString(JsonObject.serializer(), obj)
            .toRequestBody("application/json".toMediaType())
        okHttp.newCall(builder("/api/sessions/$sessionId").patch(payload).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw HermesApiException(resp.code, "update session failed")
        }
    }

    suspend fun deleteSession(sessionId: String) = withContext(Dispatchers.IO) {
        okHttp.newCall(builder("/api/sessions/$sessionId").delete().build()).execute().use { resp ->
            if (!resp.isSuccessful) throw HermesApiException(resp.code, "delete session failed")
        }
    }

    suspend fun setActiveProfile(name: String) = withContext(Dispatchers.IO) {
        val obj: JsonObject = buildJsonObject { put("name", name) }
        val payload = json.encodeToString(JsonObject.serializer(), obj)
            .toRequestBody("application/json".toMediaType())
        okHttp.newCall(builder("/api/profiles/active").post(payload).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw HermesApiException(resp.code, "set active profile failed")
        }
    }
}
