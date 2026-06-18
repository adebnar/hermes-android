package com.hermes.client.domain

enum class Role { USER, ASSISTANT, SYSTEM }
enum class ToolStatus { RUNNING, DONE }

data class Session(
    val id: String,
    val title: String,
    val model: String?,
    val provider: String?,
    val messageCount: Int,
    val profile: String?,
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
