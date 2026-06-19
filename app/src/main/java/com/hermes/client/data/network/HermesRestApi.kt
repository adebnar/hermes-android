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

    suspend fun sessions(limit: Int, offset: Int, profile: String? = null): List<SessionDto> =
        get<SessionListDto>(
            "/api/sessions?limit=$limit&offset=$offset&order=recent${profileParam(profile)}",
        ).sessions

    suspend fun messages(sessionId: String, profile: String? = null): List<MessageDto> =
        get<MessagesDto>("/api/sessions/$sessionId/messages${profileParam(profile, first = true)}").messages

    /** "&profile=x" (or "?profile=x" when [first]) — empty when profile is null/blank. */
    private fun profileParam(profile: String?, first: Boolean = false): String {
        if (profile.isNullOrBlank()) return ""
        val sep = if (first) "?" else "&"
        return "${sep}profile=${java.net.URLEncoder.encode(profile, "UTF-8")}"
    }

    suspend fun profiles(): List<ProfileDto> = get<ProfilesDto>("/api/profiles").profiles

    /** The gateway's currently-active profile name (current takes precedence over active). */
    suspend fun activeProfile(): String? =
        get<ActiveProfileDto>("/api/profiles/active").let { it.current ?: it.active }

    /** Raw provider list (each provider carries its model strings + an is_current flag). */
    suspend fun modelProviders(): List<ModelProviderDto> =
        get<ModelOptionsDto>("/api/model/options").providers

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

    suspend fun sessionStats(profile: String? = null): SessionStatsDto =
        get("/api/sessions/stats${profileParam(profile, first = true)}")

    suspend fun searchSessions(query: String, profile: String? = null): List<SearchResultDto> {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        return get<SearchResultsDto>("/api/sessions/search?q=$q&limit=30${profileParam(profile)}").results
    }

    suspend fun archivedSessions(profile: String? = null): List<SessionDto> =
        get<SessionListDto>(
            "/api/sessions?archived=only&limit=50&order=recent${profileParam(profile)}",
        ).sessions

    suspend fun cronJobs(profile: String? = null): List<CronJobDto> =
        get("/api/cron/jobs${profileParam(profile, first = true)}")

    suspend fun skills(): List<SkillDto> = get("/api/skills")

    suspend fun toggleSkill(name: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        val obj: JsonObject = buildJsonObject { put("name", name); put("enabled", enabled) }
        val payload = json.encodeToString(JsonObject.serializer(), obj)
            .toRequestBody("application/json".toMediaType())
        okHttp.newCall(builder("/api/skills/toggle").put(payload).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw HermesApiException(resp.code, "toggle skill failed")
        }
    }

    suspend fun toolsets(): List<ToolsetDto> = get("/api/tools/toolsets")

    suspend fun setActiveProfile(name: String) = withContext(Dispatchers.IO) {
        val obj: JsonObject = buildJsonObject { put("name", name) }
        val payload = json.encodeToString(JsonObject.serializer(), obj)
            .toRequestBody("application/json".toMediaType())
        okHttp.newCall(builder("/api/profiles/active").post(payload).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw HermesApiException(resp.code, "set active profile failed")
        }
    }
}
