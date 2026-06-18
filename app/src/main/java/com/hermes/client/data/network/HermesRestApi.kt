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
        return Request.Builder()
            .url("${cfg.baseUrl}$path")
            .header("X-Hermes-Session-Token", cfg.token)
    }

    private suspend inline fun <reified T> get(path: String): T = withContext(Dispatchers.IO) {
        okHttp.newCall(builder(path).get().build()).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw HermesApiException(resp.code, body.ifBlank { "HTTP ${resp.code}" })
            json.decodeFromString<T>(body)
        }
    }

    suspend fun status(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            okHttp.newCall(builder("/api/status").get().build()).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    suspend fun sessions(limit: Int, offset: Int): List<SessionDto> =
        get<SessionListDto>("/api/sessions?limit=$limit&offset=$offset&order=recent").sessions

    suspend fun messages(sessionId: String): List<MessageDto> =
        get<MessagesDto>("/api/sessions/$sessionId/messages").messages

    suspend fun profiles(): List<ProfileDto> = get<ProfilesDto>("/api/profiles").profiles

    suspend fun modelOptions(): List<ModelOptionDto> = get<ModelOptionsDto>("/api/model/options").options

    suspend fun setModel(provider: String, model: String) = withContext(Dispatchers.IO) {
        val obj: JsonObject = buildJsonObject {
            put("provider", provider)
            put("model", model)
        }
        val payload = json.encodeToString(JsonObject.serializer(), obj)
            .toRequestBody("application/json".toMediaType())
        okHttp.newCall(builder("/api/model/set").post(payload).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw HermesApiException(resp.code, "set model failed")
        }
    }
}
