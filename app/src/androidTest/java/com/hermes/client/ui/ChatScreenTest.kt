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
