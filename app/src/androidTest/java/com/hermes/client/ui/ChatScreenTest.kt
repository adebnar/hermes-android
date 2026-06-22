package com.hermes.client.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role
import com.hermes.client.ui.chat.ChatMessageList
import com.hermes.client.ui.chat.ChatUiState
import com.hermes.client.ui.theme.HermesTheme
import org.junit.Rule
import org.junit.Test

class ChatScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test fun renders_user_and_assistant_messages() {
        val state = ChatUiState(
            messages = listOf(
                ChatMessage(id = "u1", role = Role.USER, text = "Hello"),
                ChatMessage(id = "a1", role = Role.ASSISTANT, text = "Hi there"),
            ),
        )
        rule.setContent { HermesTheme { ChatMessageList(state = state) } }
        rule.onNodeWithText("Hello").assertIsDisplayed()
        rule.onNodeWithText("Hi there").assertIsDisplayed()
    }

    // Opening an existing session must land at the newest message, not the top —
    // otherwise the latest reply (and any new response) sits below the fold and looks
    // missing, forcing the user to scroll to the bottom by hand.
    @Test fun opening_session_lands_on_latest_message() {
        val last = "message number 120 — this is a longer line of body text so the thread overflows the screen and the final item only renders if the list scrolled to the bottom"
        val msgs = (1..120).map { i ->
            ChatMessage(
                id = "m$i",
                role = if (i % 2 == 0) Role.ASSISTANT else Role.USER,
                text = if (i == 120) last
                else "message number $i — this is a longer line of body text so the thread overflows the screen and the final item only renders if the list scrolled to the bottom",
            )
        }
        // Mimic the real flow: the screen composes empty, then history loads asynchronously
        // and populates the list (so by the time it fills, the list is already laid out at the
        // top). Composing the full list up front hides the bug because the first auto-scroll
        // effect runs before layout (empty visibleItems) and scrolls anyway.
        val listState = androidx.compose.foundation.lazy.LazyListState()
        val state = androidx.compose.runtime.mutableStateOf(ChatUiState(messages = emptyList()))
        rule.setContent { HermesTheme { ChatMessageList(state = state.value, listState = listState) } }
        rule.waitForIdle()
        state.value = ChatUiState(messages = msgs)
        // The scroll-to-bottom converges across layout passes (item heights are measured
        // lazily), so drive frames until the list reaches the absolute bottom — the newest
        // message is the last visible item and there is nothing left to scroll past.
        rule.waitUntil(timeoutMillis = 5_000) {
            (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) >= 119 &&
                !listState.canScrollForward
        }
    }

    // Regression: the gateway reuses the model name as the message id across a
    // session's turns, so loaded history can contain several messages with the same
    // id. The chat list must not crash on duplicate ids ("Key X was already used").
    @Test fun duplicate_message_ids_do_not_crash() {
        val state = ChatUiState(
            messages = listOf(
                ChatMessage(id = "gemma", role = Role.ASSISTANT, text = "first reply"),
                ChatMessage(id = "gemma", role = Role.ASSISTANT, text = "second reply"),
            ),
        )
        rule.setContent { HermesTheme { ChatMessageList(state = state) } }
        rule.onNodeWithText("first reply").assertIsDisplayed()
        rule.onNodeWithText("second reply").assertIsDisplayed()
    }
}
