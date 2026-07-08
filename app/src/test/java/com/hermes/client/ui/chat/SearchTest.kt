package com.hermes.client.ui.chat

import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchTest {
    private fun m(id: String, role: Role, text: String) = ChatMessage(id = id, role = role, text = text)
    private val msgs = listOf(
        m("u0", Role.USER, "Summarize the Stripe incident"),
        m("a0", Role.ASSISTANT, "The stripe charge failed for a null customer_id"),
        m("s0", Role.SYSTEM, "📎 image attached"),
        m("u1", Role.USER, "open a PR"),
    )

    @Test fun blank_query_returns_empty() {
        assertEquals(emptyList<Int>(), matchIndices(msgs, ""))
        assertEquals(emptyList<Int>(), matchIndices(msgs, "   "))
    }
    @Test fun case_insensitive_substring() {
        assertEquals(listOf(0, 1), matchIndices(msgs, "STRIPE"))
    }
    @Test fun returns_all_matches_in_order() {
        assertEquals(listOf(0, 1, 3), matchIndices(msgs, "p"))
    }
    @Test fun no_match_returns_empty() {
        assertEquals(emptyList<Int>(), matchIndices(msgs, "zzz"))
    }
    @Test fun matches_system_and_assistant_text() {
        assertEquals(listOf(2), matchIndices(msgs, "attached"))
        assertEquals(listOf(1), matchIndices(msgs, "customer_id"))
    }
}
