package com.hermes.client.domain

enum class Role { USER, ASSISTANT, SYSTEM }
enum class ToolStatus { RUNNING, DONE }

data class Session(
    val id: String,
    val title: String,
    val model: String?,
    val provider: String?,
    val messageCount: Int,
    // The profile this session belongs to. From the cross-profile list this is always set;
    // the default profile is normalized to "default" so grouping, pin tokens, and profile
    // switching share one stable key. Used as the top grouping tier.
    val profile: String?,
    // Workspace = basename of the session's cwd ("No workspace" when none), used for grouping.
    val workspace: String = "No workspace",
    val archived: Boolean = false,
    val source: String? = null,
    // Epoch millis of last activity (from the gateway's last_active seconds), for recency sorting
    // and the Mission Control feed. Null when the gateway omits it.
    val lastActive: Long? = null,
)

data class ToolCall(
    val id: String,
    val name: String,
    val status: ToolStatus,
    val output: String = "",
)

data class ChatMessage(
    val id: String,
    val role: Role,
    val text: String,
    val tools: List<ToolCall> = emptyList(),
    val thinking: String = "",
    val isStreaming: Boolean = false,
    val isError: Boolean = false,
    val interrupted: Boolean = false,
)
