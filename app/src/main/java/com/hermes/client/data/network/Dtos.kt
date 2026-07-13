package com.hermes.client.data.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable data class StatusDto(val ok: Boolean = true)

@Serializable data class GatewayStatusDto(
    val version: String? = null,
    @SerialName("gateway_running") val gatewayRunning: Boolean = false,
    @SerialName("gateway_state") val gatewayState: String? = null,
)

@Serializable data class SessionDto(
    // The gateway returns the session id under "id" (not "session_id").
    @SerialName("id") val sessionId: String,
    val title: String? = null,
    val model: String? = null,
    val provider: String? = null,
    @SerialName("last_active") val lastActive: Double? = null,
    @SerialName("message_count") val messageCount: Int = 0,
    val profile: String? = null,
    @SerialName("is_default_profile") val isDefaultProfile: Boolean = false,
    val archived: Boolean = false,
    val cwd: String? = null,
    val source: String? = null,
    @SerialName("git_branch") val gitBranch: String? = null,
    @SerialName("git_repo_root") val gitRepoRoot: String? = null,
)
@Serializable data class SessionListDto(val sessions: List<SessionDto> = emptyList())

/**
 * Cross-profile session list (`/api/profiles/sessions`). Unlike `/api/sessions?profile=`,
 * every session here carries its true `profile`, so the list can show all tenants at once
 * with each session under the profile it actually belongs to. [profileTotals] maps profile
 * name → session count (used for group headers); [errors] lists any profiles that failed to load.
 */
@Serializable data class ProfileSessionsDto(
    val sessions: List<SessionDto> = emptyList(),
    val total: Int = 0,
    @SerialName("profile_totals") val profileTotals: Map<String, Int> = emptyMap(),
    val errors: List<String> = emptyList(),
)

@Serializable data class MessageDto(
    // The gateway returns a numeric message id, and content may be null (e.g. tool-only turns).
    val id: Int? = null,
    val role: String,
    val content: String? = null,
)
@Serializable data class MessagesDto(val messages: List<MessageDto> = emptyList())

@Serializable data class ProfileDto(
    val name: String,
    @SerialName("is_default") val isDefault: Boolean = false,
)
@Serializable data class ProfilesDto(val profiles: List<ProfileDto> = emptyList())
@Serializable data class ActiveProfileDto(val active: String? = null, val current: String? = null)

/** Flattened, UI-facing option (provider slug + fully-qualified model string). */
data class ModelOptionDto(
    val provider: String,
    val model: String,
    val label: String? = null,
)

/** Real /api/model/options shape: providers each carry a list of model-name strings. */
@Serializable data class ModelProviderDto(
    val slug: String,
    val name: String? = null,
    @SerialName("is_current") val isCurrent: Boolean = false,
    val models: List<String> = emptyList(),
)
@Serializable data class ModelOptionsDto(val providers: List<ModelProviderDto> = emptyList())

@Serializable data class SessionStatsDto(
    val total: Int = 0,
    @SerialName("active_store") val activeStore: Int = 0,
    val archived: Int = 0,
    val messages: Int = 0,
)

@Serializable data class SearchResultDto(
    @SerialName("session_id") val sessionId: String,
    val snippet: String? = null,
    val model: String? = null,
    val role: String? = null,
)
@Serializable data class SearchResultsDto(val results: List<SearchResultDto> = emptyList())

@Serializable data class SkillDto(
    val name: String,
    val description: String? = null,
    val category: String? = null,
    val enabled: Boolean = false,
)

@Serializable data class CronScheduleDto(
    val kind: String? = null,
    val expr: String? = null,
    val display: String? = null,
)
@Serializable data class CronJobDto(
    val id: String,
    val name: String? = null,
    val schedule: CronScheduleDto? = null,
    @SerialName("schedule_display") val scheduleDisplay: String? = null,
    val enabled: Boolean = true,
    val state: String? = null,
    // These arrive as ISO-8601 strings (e.g. "2026-06-20T09:00:00-05:00"), not epoch numbers.
    @SerialName("paused_at") val pausedAt: String? = null,
    @SerialName("next_run_at") val nextRunAt: String? = null,
    @SerialName("last_run_at") val lastRunAt: String? = null,
    @SerialName("last_status") val lastStatus: String? = null,
    @SerialName("last_error") val lastError: String? = null,
    val profile: String? = null,
    val model: String? = null,
    val prompt: String? = null,
) {
    val scheduleText: String get() = scheduleDisplay ?: schedule?.display ?: schedule?.expr ?: "—"
    val isPaused: Boolean get() = pausedAt != null || state == "paused"
}

@Serializable data class CronRunDto(
    val id: String,
    @SerialName("started_at") val startedAt: Double? = null,
    @SerialName("ended_at") val endedAt: Double? = null,
    @SerialName("end_reason") val endReason: String? = null,
    @SerialName("output_tokens") val outputTokens: Int? = null,
)
@Serializable data class CronRunsDto(val runs: List<CronRunDto> = emptyList())

@Serializable data class MessagingEnvVarDto(
    val key: String,
    val required: Boolean = false,
    @SerialName("is_set") val isSet: Boolean = false,
    val description: String? = null,
    val prompt: String? = null,
    val url: String? = null,
    @SerialName("is_password") val isPassword: Boolean = false,
)
@Serializable data class MessagingPlatformDto(
    val id: String,
    val name: String? = null,
    val description: String? = null,
    val enabled: Boolean = false,
    val configured: Boolean = false,
    @SerialName("gateway_running") val gatewayRunning: Boolean = false,
    val state: String? = null,
    @SerialName("docs_url") val docsUrl: String? = null,
    @SerialName("env_vars") val envVars: List<MessagingEnvVarDto> = emptyList(),
)
@Serializable data class MessagingPlatformsDto(val platforms: List<MessagingPlatformDto> = emptyList())

@Serializable data class UsageDayDto(
    val day: String,
    @SerialName("input_tokens") val inputTokens: Long = 0,
    @SerialName("output_tokens") val outputTokens: Long = 0,
    @SerialName("estimated_cost") val estimatedCost: Double = 0.0,
    val sessions: Int = 0,
    @SerialName("api_calls") val apiCalls: Int = 0,
)
@Serializable data class UsageDto(val daily: List<UsageDayDto> = emptyList())

@Serializable data class ModelUsageDto(
    val model: String,
    val provider: String? = null,
    @SerialName("input_tokens") val inputTokens: Long = 0,
    @SerialName("output_tokens") val outputTokens: Long = 0,
    @SerialName("estimated_cost") val estimatedCost: Double = 0.0,
    val sessions: Int = 0,
    @SerialName("api_calls") val apiCalls: Int = 0,
)
@Serializable data class ModelsUsageDto(val models: List<ModelUsageDto> = emptyList())

@Serializable data class EnvVarDto(
    @SerialName("is_set") val isSet: Boolean = false,
    @SerialName("redacted_value") val redactedValue: String? = null,
    val description: String? = null,
    val category: String? = null,
    @SerialName("is_password") val isPassword: Boolean = false,
    val advanced: Boolean = false,
)

@Serializable data class ToolsetDto(
    val name: String,
    val label: String? = null,
    val description: String? = null,
    val enabled: Boolean = false,
    val available: Boolean = true,
    val tools: List<String> = emptyList(),
)

/**
 * Gateway `projects.tree` result. Single-profile (the connected gateway's bound profile).
 * `scoped_session_ids` is parsed but unused in v1 (Sessions mode shows the flat REST list
 * independently). `icon` is an unknown key here and intentionally ignored (YAGNI).
 */
@Serializable data class ProjectTreeDto(
    val projects: List<ProjectNodeDto> = emptyList(),
    @SerialName("active_id") val activeId: String? = null,
)

@Serializable data class ProjectNodeDto(
    val id: String,
    val label: String = "",
    val path: String? = null,
    val color: String? = null,
    val isAuto: Boolean = false,
    val sessionCount: Int = 0,
    val lastActive: Double? = null,
    val repos: List<RepoDto> = emptyList(),
    // On projects.tree these are up to preview_limit newest rows; on project_sessions it's empty.
    val previewSessions: List<SessionDto> = emptyList(),
)

@Serializable data class RepoDto(
    val id: String,
    val label: String = "",
    val path: String? = null,
    val sessionCount: Int = 0,
    // Gateway calls the branch lanes "groups".
    val groups: List<LaneDto> = emptyList(),
)

@Serializable data class LaneDto(
    val id: String,
    val label: String = "",
    val path: String? = null,
    val isMain: Boolean = false,
    // Lane sessions are empty on projects.tree (counts only) and hydrated on project_sessions.
    val sessions: List<SessionDto> = emptyList(),
)

/** Gateway `projects.project_sessions` result — a single hydrated node (or null). */
@Serializable data class ProjectSessionsResultDto(
    val project: ProjectNodeDto? = null,
)
