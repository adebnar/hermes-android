package com.hermes.client.ui.chat

import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role

/** The text of the most recent USER message, or null if there is none (used to re-ask). */
fun lastUserMessageText(messages: List<ChatMessage>): String? =
    messages.lastOrNull { it.role == Role.USER }?.text?.takeIf { it.isNotBlank() }

/**
 * Render the conversation to a plain-text, role-labeled transcript. Body text is verbatim
 * (markdown preserved). Blank-text turns (tool-only / still-streaming stubs) are skipped.
 */
fun transcriptText(messages: List<ChatMessage>): String =
    messages
        .filter { it.text.isNotBlank() }
        .joinToString("\n\n") { m ->
            val label = when {
                m.isError -> "Error"
                m.role == Role.SYSTEM -> "System"
                m.role == Role.USER -> "You"
                else -> "Assistant"
            }
            "$label:\n${m.text}"
        }
