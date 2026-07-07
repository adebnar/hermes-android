package com.hermes.client.ui.chat

import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role

/** The text of the most recent USER message, or null if there is none (used to re-ask). */
fun lastUserMessageText(messages: List<ChatMessage>): String? =
    messages.lastOrNull { it.role == Role.USER }?.text?.takeIf { it.isNotBlank() }
