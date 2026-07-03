package com.hermes.client.ui.chat

import com.hermes.client.data.network.ServerEvent
import com.hermes.client.data.network.str
import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role
import com.hermes.client.domain.ToolCall
import com.hermes.client.domain.ToolStatus

data class ApprovalRequest(val prompt: String)
data class ClarifyRequest(val question: String, val options: List<String>)

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val pendingApproval: ApprovalRequest? = null,
    val pendingClarify: ClarifyRequest? = null,
    val isGenerating: Boolean = false,
) {
    companion object { fun empty() = ChatUiState() }
}

fun ChatUiState.withUserMessage(text: String): ChatUiState =
    copy(
        messages = messages + ChatMessage(id = "u-${messages.size}", role = Role.USER, text = text),
        isGenerating = true,
    )


/** Pure reducer: folds one server event into the chat state. */
fun reduce(state: ChatUiState, event: ServerEvent): ChatUiState {
    // Targets the last STREAMING assistant message.
    fun mutateLastAssistant(block: (ChatMessage) -> ChatMessage): ChatUiState {
        val idx = state.messages.indexOfLast { it.role == Role.ASSISTANT && it.isStreaming }
        if (idx < 0) return state
        val updated = state.messages.toMutableList()
        updated[idx] = block(updated[idx])
        return state.copy(messages = updated)
    }

    // Targets the last assistant message regardless of streaming state.
    fun mutateLastAssistantAny(block: (ChatMessage) -> ChatMessage): ChatUiState {
        val idx = state.messages.indexOfLast { it.role == Role.ASSISTANT }
        if (idx < 0) return state
        val updated = state.messages.toMutableList()
        updated[idx] = block(updated[idx])
        return state.copy(messages = updated)
    }

    return when (event.type) {
        "message.start" -> state.copy(
            messages = state.messages + ChatMessage(
                // The gateway's message_id is NOT unique across turns — it sends the
                // model/agent name (e.g. "gemma"), reused every turn. Used alone as a
                // LazyColumn key it collides on the second turn and crashes the app, so
                // prefix the message position (monotonic) to guarantee a unique, stable
                // id. message_id isn't read anywhere else (deltas/tools route by
                // indexOfLast / tool_id), so this is the only place it matters.
                id = "a-${state.messages.size}-${event.str("message_id") ?: "msg"}",
                role = Role.ASSISTANT, text = "", isStreaming = true,
            ),
            isGenerating = true,
        )
        // Gateway streams text under payload.text (not "delta"/"content").
        "message.delta" -> mutateLastAssistant { it.copy(text = it.text + (event.str("text") ?: "")) }
        // Real reasoning arrives as reasoning.delta/reasoning.available (payload.text).
        // thinking.delta is only a transient spinner status, so it's ignored (else branch).
        "reasoning.delta", "reasoning.available" ->
            mutateLastAssistant { it.copy(thinking = it.thinking + (event.str("text") ?: "")) }
        "message.complete" -> mutateLastAssistant {
            it.copy(text = (event.str("text") ?: event.str("rendered")) ?: it.text, isStreaming = false)
        }.copy(isGenerating = false)
        "tool.start" -> mutateLastAssistant {
            it.copy(tools = it.tools + ToolCall(
                id = event.str("tool_id") ?: "t-${it.tools.size}",
                name = event.str("name") ?: "tool",
                status = ToolStatus.RUNNING,
            ))
        }
        "tool.complete" -> mutateLastAssistantAny { msg ->
            val tid = event.str("tool_id")
            msg.copy(tools = msg.tools.map {
                if (it.id == tid) it.copy(status = ToolStatus.DONE, output = event.str("result") ?: "") else it
            })
        }
        "approval.request" -> state.copy(pendingApproval = ApprovalRequest(event.str("prompt") ?: ""))
        "clarify.request" -> state.copy(
            pendingClarify = ClarifyRequest(event.str("question") ?: "", emptyList()),
        )
        "error" -> state.copy(
            messages = state.messages + ChatMessage(
                id = "e-${state.messages.size}", role = Role.SYSTEM,
                text = event.str("message") ?: "error", isError = true,
            ),
            isGenerating = false,
        )
        else -> state
    }
}

/**
 * Pure helper: marks the current generation as interrupted.
 * - Finds the last assistant message with isStreaming==true; if present, sets
 *   isStreaming=false and interrupted=true on it.
 * - Always sets isGenerating=false, whether or not a streaming message was found.
 */
fun ChatUiState.markInterrupted(): ChatUiState {
    val idx = messages.indexOfLast { it.role == Role.ASSISTANT && it.isStreaming }
    val newMessages = if (idx >= 0) {
        messages.toMutableList().also { list ->
            list[idx] = list[idx].copy(isStreaming = false, interrupted = true)
        }.toList()
    } else {
        messages
    }
    return copy(messages = newMessages, isGenerating = false)
}
