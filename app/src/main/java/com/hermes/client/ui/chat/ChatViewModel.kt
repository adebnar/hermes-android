package com.hermes.client.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.network.ConnectionState
import com.hermes.client.data.network.HermesApiException
import com.hermes.client.data.repository.ChatRepository
import com.hermes.client.data.repository.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
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

    val connectionState: StateFlow<ConnectionState> = chat.connectionState

    // I1: expose 401 unauthorized so the nav layer can route back to Setup
    private val _unauthorized = MutableStateFlow(false)
    val unauthorized: StateFlow<Boolean> = _unauthorized.asStateFlow()

    private var sessionId: String = ""
    private var collectJob: Job? = null
    private var connJob: Job? = null

    fun open(id: String) {
        sessionId = id
        connJob?.cancel()
        viewModelScope.launch {
            try {
                val history = sessions.history(id)
                _state.value = ChatUiState(messages = history)
            } catch (e: HermesApiException) {
                if (e.code == 401) { _unauthorized.value = true; return@launch }
                _state.value = ChatUiState(messages = emptyList())
            }
            chat.resume(id)
        }
        collectJob?.cancel()
        collectJob = viewModelScope.launch {
            chat.events.filter { it.sessionId == null || it.sessionId == sessionId }
                .onEach { event -> _state.value = reduce(_state.value, event) }
                .collect {}
        }
        // C2 + I3: watch connection transitions
        connJob = viewModelScope.launch {
            var prev: ConnectionState? = null
            chat.connectionState.collect { cur ->
                // I3: entering Reconnecting or Error while generating → mark interrupted
                if ((cur is ConnectionState.Reconnecting || cur is ConnectionState.Error)
                    && _state.value.isGenerating
                ) {
                    _state.value = _state.value.markInterrupted()
                }
                // C2: reconnect cycle completed (Reconnecting → Connected) → re-attach agent stream
                // Guard: prev must be Reconnecting (not null) to skip the very first Connected transition
                if (cur is ConnectionState.Connected && prev is ConnectionState.Reconnecting) {
                    launch { chat.resume(sessionId) }
                }
                prev = cur
            }
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
