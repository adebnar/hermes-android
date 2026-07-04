package com.hermes.client.ui.activity

import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role

/** Max length of the tool-result fallback summary before truncation. */
const val CRON_RESPONSE_MAX = 500

/**
 * A cron run's "response" from its session messages: the last assistant message with text; else
 * the last tool result summary; else a placeholder. Pure — unit-tested without network or clock.
 */
fun cronResponse(messages: List<ChatMessage>): String {
    messages.lastOrNull { it.role == Role.ASSISTANT && it.text.isNotBlank() }
        ?.let { return it.text.trim() }
    messages.lastOrNull { m -> m.tools.any { it.output.isNotBlank() } }
        ?.let { m ->
            val tool = m.tools.last { it.output.isNotBlank() }
            val summary = "${tool.name}: ${tool.output.trim()}"
            return if (summary.length > CRON_RESPONSE_MAX) summary.take(CRON_RESPONSE_MAX) + "…" else summary
        }
    return "No text output."
}
