package com.hermes.client.ui.chat

import com.hermes.client.domain.ChatMessage

/** Indices of [messages] whose text contains [query] (case-insensitive); empty for a blank query. */
fun matchIndices(messages: List<ChatMessage>, query: String): List<Int> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()
    return messages.indices.filter { messages[it].text.contains(q, ignoreCase = true) }
}
