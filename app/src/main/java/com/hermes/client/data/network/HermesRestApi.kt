package com.hermes.client.data.network

import com.hermes.client.data.auth.GatewayConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        // Keep the diagnostic log's redaction current with whatever token is active, so a
        // shared log can never contain the session token in plain text.
        com.hermes.client.data.diagnostics.DebugLog.setTokenToRedact(cfg.token)
        // Trim trailing slashes so a user-entered "http://host:9119/" doesn't produce
        // "//api/..." — the gateway routes a double slash to its web UI (HTML), not the API.
        val b = Request.Builder().url("${cfg.baseUrl.trimEnd('/')}$path")
        if (cfg.token.isNotBlank()) b.header("X-Hermes-Session-Token", cfg.token)
        return b
    }

    private suspend inline fun <reified T> get(path: String): T = withContext(Dispatchers.IO) {
        com.hermes.client.data.diagnostics.DebugLog.log("rest", "GET $path")
        okHttp.newCall(builder(path).get().build()).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                com.hermes.client.data.diagnostics.DebugLog.log(
                    "rest", "GET $path ← ${resp.code} ${body.take(200)}",
                )
                throw HermesApiException(resp.code, body.ifBlank { "HTTP ${resp.code}" })
            }
            com.hermes.client.data.diagnostics.DebugLog.log("rest", "GET $path ← ${resp.code}")
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

    /** Public /api/status — gateway version + running state. */
    suspend fun gatewayStatus(): GatewayStatusDto = get("/api/status")

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

    suspend fun cronJob(jobId: String, profile: String? = null): CronJobDto =
        get("/api/cron/jobs/$jobId${profileParam(profile, first = true)}")

    suspend fun cronRuns(jobId: String, profile: String? = null): List<CronRunDto> =
        get<CronRunsDto>("/api/cron/jobs/$jobId/runs${profileParam(profile, first = true)}").runs

    /** POST a cron action (pause | resume | trigger); empty body. */
    private suspend fun cronAction(jobId: String, action: String, profile: String?) =
        withContext(Dispatchers.IO) {
            val body = "{}".toRequestBody("application/json".toMediaType())
            val path = "/api/cron/jobs/$jobId/$action${profileParam(profile, first = true)}"
            okHttp.newCall(builder(path).post(body).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw HermesApiException(resp.code, "$action failed")
            }
        }

    suspend fun pauseCron(jobId: String, profile: String? = null) = cronAction(jobId, "pause", profile)
    suspend fun resumeCron(jobId: String, profile: String? = null) = cronAction(jobId, "resume", profile)
    suspend fun triggerCron(jobId: String, profile: String? = null) = cronAction(jobId, "trigger", profile)

    suspend fun createCron(prompt: String, schedule: String, name: String, profile: String? = null) =
        withContext(Dispatchers.IO) {
            val obj = buildJsonObject {
                put("prompt", prompt); put("schedule", schedule)
                if (name.isNotBlank()) put("name", name)
            }
            val payload = json.encodeToString(JsonObject.serializer(), obj)
                .toRequestBody("application/json".toMediaType())
            okHttp.newCall(builder("/api/cron/jobs${profileParam(profile, first = true)}").post(payload).build())
                .execute().use { resp ->
                    if (!resp.isSuccessful) {
                        val body = resp.body?.string().orEmpty().take(160)
                        throw HermesApiException(resp.code, "create cron failed: $body")
                    }
                }
        }

    suspend fun updateCron(jobId: String, prompt: String, schedule: String, name: String, profile: String? = null) =
        withContext(Dispatchers.IO) {
            val obj = buildJsonObject {
                put("prompt", prompt); put("schedule", schedule); put("name", name)
            }
            val payload = json.encodeToString(JsonObject.serializer(), obj)
                .toRequestBody("application/json".toMediaType())
            okHttp.newCall(builder("/api/cron/jobs/$jobId${profileParam(profile, first = true)}").put(payload).build())
                .execute().use { resp ->
                    if (!resp.isSuccessful) {
                        val body = resp.body?.string().orEmpty().take(160)
                        throw HermesApiException(resp.code, "update cron failed: $body")
                    }
                }
        }

    suspend fun deleteCron(jobId: String, profile: String? = null) = withContext(Dispatchers.IO) {
        okHttp.newCall(builder("/api/cron/jobs/$jobId${profileParam(profile, first = true)}").delete().build())
            .execute().use { resp ->
                if (!resp.isSuccessful) throw HermesApiException(resp.code, "delete cron failed")
            }
    }

    suspend fun analyticsUsage(profile: String? = null): UsageDto =
        get("/api/analytics/usage${profileParam(profile, first = true)}")

    suspend fun analyticsModels(profile: String? = null): List<ModelUsageDto> =
        get<ModelsUsageDto>("/api/analytics/models${profileParam(profile, first = true)}").models

    // ---- Config (whole-object GET-modify-PUT so no fields are ever dropped) ----

    suspend fun getConfig(profile: String? = null): JsonObject =
        get("/api/config${profileParam(profile, first = true)}")

    suspend fun putConfig(config: JsonObject, profile: String? = null) = withContext(Dispatchers.IO) {
        // PUT /api/config expects the config wrapped as {"config": {...}} (422 otherwise).
        val wrapped = JsonObject(mapOf("config" to config))
        val payload = json.encodeToString(JsonObject.serializer(), wrapped)
            .toRequestBody("application/json".toMediaType())
        okHttp.newCall(builder("/api/config${profileParam(profile, first = true)}").put(payload).build())
            .execute().use { resp ->
                if (!resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty().take(180)
                    throw HermesApiException(resp.code, "HTTP ${resp.code}: $body")
                }
            }
    }

    suspend fun setProfileModel(name: String, provider: String, model: String) = withContext(Dispatchers.IO) {
        val obj = buildJsonObject { put("provider", provider); put("model", model) }
        val payload = json.encodeToString(JsonObject.serializer(), obj)
            .toRequestBody("application/json".toMediaType())
        okHttp.newCall(builder("/api/profiles/$name/model").put(payload).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw HermesApiException(resp.code, "set profile model failed")
        }
    }

    // ---- Env / API keys (per-key endpoints — safe by design) ----

    suspend fun envVars(profile: String? = null): Map<String, EnvVarDto> =
        get("/api/env${profileParam(profile, first = true)}")

    suspend fun setEnv(key: String, value: String, profile: String? = null) = withContext(Dispatchers.IO) {
        val obj = buildJsonObject {
            put("key", key); put("value", value); if (profile != null) put("profile", profile)
        }
        val payload = json.encodeToString(JsonObject.serializer(), obj)
            .toRequestBody("application/json".toMediaType())
        okHttp.newCall(builder("/api/env").put(payload).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw HermesApiException(resp.code, "set env failed")
        }
    }

    suspend fun revealEnv(key: String, profile: String? = null): String = withContext(Dispatchers.IO) {
        val obj = buildJsonObject { put("key", key); if (profile != null) put("profile", profile) }
        val payload = json.encodeToString(JsonObject.serializer(), obj)
            .toRequestBody("application/json".toMediaType())
        okHttp.newCall(builder("/api/env/reveal").post(payload).build()).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw HermesApiException(resp.code, "reveal env failed")
            json.decodeFromString<JsonObject>(body)["value"]?.jsonPrimitive?.content ?: ""
        }
    }

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

    suspend fun messagingPlatforms(): List<MessagingPlatformDto> =
        get<MessagingPlatformsDto>("/api/messaging/platforms").platforms

    suspend fun setMessagingEnabled(platformId: String, enabled: Boolean) =
        configureMessaging(platformId, emptyMap(), enabled)

    /** Configure a messaging platform: set env vars and/or enable/disable it. */
    suspend fun configureMessaging(
        platformId: String,
        env: Map<String, String>,
        enabled: Boolean?,
    ) = withContext(Dispatchers.IO) {
        val obj = buildJsonObject {
            if (enabled != null) put("enabled", enabled)
            if (env.isNotEmpty()) put("env", buildJsonObject { env.forEach { (k, v) -> put(k, v) } })
        }
        val payload = json.encodeToString(JsonObject.serializer(), obj)
            .toRequestBody("application/json".toMediaType())
        okHttp.newCall(builder("/api/messaging/platforms/$platformId").put(payload).build())
            .execute().use { resp ->
                if (!resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty().take(180)
                    throw HermesApiException(resp.code, "configure platform failed: $body")
                }
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
