package com.hermes.client.data.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable data class StatusDto(val ok: Boolean = true)

@Serializable data class SessionDto(
    @SerialName("session_id") val sessionId: String,
    val title: String? = null,
    val model: String? = null,
    val provider: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("message_count") val messageCount: Int = 0,
    val profile: String? = null,
)
@Serializable data class SessionListDto(val sessions: List<SessionDto> = emptyList())

@Serializable data class MessageDto(
    val id: String? = null,
    val role: String,
    val content: String = "",
)
@Serializable data class MessagesDto(val messages: List<MessageDto> = emptyList())

@Serializable data class ProfileDto(val name: String, val active: Boolean = false)
@Serializable data class ProfilesDto(val profiles: List<ProfileDto> = emptyList())

@Serializable data class ModelOptionDto(
    val provider: String,
    val model: String,
    val label: String? = null,
)
@Serializable data class ModelOptionsDto(val options: List<ModelOptionDto> = emptyList())
