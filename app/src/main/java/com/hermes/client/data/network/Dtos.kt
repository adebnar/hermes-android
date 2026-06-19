package com.hermes.client.data.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable data class StatusDto(val ok: Boolean = true)

@Serializable data class SessionDto(
    // The gateway returns the session id under "id" (not "session_id").
    @SerialName("id") val sessionId: String,
    val title: String? = null,
    val model: String? = null,
    val provider: String? = null,
    @SerialName("last_active") val lastActive: Double? = null,
    @SerialName("message_count") val messageCount: Int = 0,
    val profile: String? = null,
)
@Serializable data class SessionListDto(val sessions: List<SessionDto> = emptyList())

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

@Serializable data class ToolsetDto(
    val name: String,
    val label: String? = null,
    val description: String? = null,
    val enabled: Boolean = false,
    val available: Boolean = true,
    val tools: List<String> = emptyList(),
)
