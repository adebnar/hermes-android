package com.hermes.client.domain

import com.hermes.client.data.network.MessageDto
import com.hermes.client.data.network.SessionDto

fun SessionDto.toDomain() = Session(
    id = sessionId,
    title = title?.ifBlank { "Untitled" } ?: "Untitled",
    model = model,
    provider = provider,
    messageCount = messageCount,
    // Normalize the default profile to a stable "default" label: the gateway may report it as
    // null or blank with is_default_profile=true, but grouping/pin-tokens/switching need one key.
    profile = profile?.ifBlank { null } ?: if (isDefaultProfile) "default" else null,
    workspace = cwd?.trimEnd('/')?.substringAfterLast('/')?.ifBlank { null } ?: "No workspace",
    archived = archived,
    source = source,
    lastActive = com.hermes.client.ui.util.secondsToEpochMs(lastActive),
)

fun MessageDto.toDomain() = ChatMessage(
    id = id?.toString() ?: "m-${hashCode()}",
    role = when (role.lowercase()) {
        "user" -> Role.USER
        "assistant" -> Role.ASSISTANT
        else -> Role.SYSTEM
    },
    text = content.orEmpty(),
)
