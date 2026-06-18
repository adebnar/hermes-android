package com.hermes.client.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.repository.ChatRepository
import com.hermes.client.data.repository.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chat: ChatRepository,
    private val sessions: SessionRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState.empty())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var sessionId: String = ""

    fun open(id: String) {
        sessionId = id
        viewModelScope.launch {
            val history = sessions.history(id)
            _state.value = ChatUiState(messages = history)
            chat.resume(id)
        }
        viewModelScope.launch(Dispatchers.Unconfined) {
            chat.events.filter { it.sessionId == null || it.sessionId == sessionId }
                .onEach { event -> _state.value = reduce(_state.value, event) }
                .collect {}
        }
    }

    fun send(text: String) {
        if (text.isBlank()) return
        _state.value = _state.value.withUserMessage(text)
        viewModelScope.launch { chat.submit(sessionId, text) }
    }

    fun stop() { viewModelScope.launch { chat.interrupt(sessionId) } }

    fun approve(approve: Boolean) {
        _state.value = _state.value.copy(pendingApproval = null)
        viewModelScope.launch { chat.respondApproval(sessionId, approve) }
    }

    fun clarify(answer: String) {
        _state.value = _state.value.copy(pendingClarify = null)
        viewModelScope.launch { chat.respondClarify(sessionId, answer) }
    }
}
